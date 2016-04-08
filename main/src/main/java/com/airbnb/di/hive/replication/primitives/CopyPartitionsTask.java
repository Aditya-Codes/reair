package com.airbnb.di.hive.replication.primitives;

import com.airbnb.di.common.FsUtils;
import com.airbnb.di.common.PathBuilder;
import com.airbnb.di.common.DistCpException;
import com.airbnb.di.hive.common.HiveObjectSpec;
import com.airbnb.di.hive.common.HiveMetastoreClient;
import com.airbnb.di.hive.common.HiveMetastoreException;
import com.airbnb.di.hive.common.HiveUtils;
import com.airbnb.di.hive.replication.configuration.Cluster;
import com.airbnb.di.hive.replication.DirectoryCopier;
import com.airbnb.di.hive.replication.RunInfo;
import com.airbnb.di.hive.replication.configuration.DestinationObjectFactory;
import com.airbnb.di.hive.replication.configuration.ObjectConflictHandler;
import com.airbnb.di.hive.replication.ReplicationUtils;
import com.airbnb.di.multiprocessing.Lock;
import com.airbnb.di.multiprocessing.LockSet;
import com.airbnb.di.multiprocessing.ParallelJobExecutor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Task that copies multiple partitions. To reduce the number of distcp jobs
 * necessary, this task tries to copy a common parent directory of those
 * partitions. However, a better solution would be to use a copy tool that can
 * copy multiple source and destination directories simultaneously.
 */
public class CopyPartitionsTask implements ReplicationTask {

    private static final Log LOG = LogFactory.getLog(
            CopyPartitionsTask.class);

    private Configuration conf;
    private DestinationObjectFactory objectModifier;
    private ObjectConflictHandler objectConflictHandler;
    private Cluster srcCluster;
    private Cluster destCluster;
    private HiveObjectSpec srcTableSpec;
    private List<String> partitionNames;
    private Optional<Path> commonDirectory;
    private ParallelJobExecutor copyPartitionsExecutor;
    private DirectoryCopier directoryCopier;

    public CopyPartitionsTask(Configuration conf,
                              DestinationObjectFactory objectModifier,
                              ObjectConflictHandler objectConflictHandler,
                              Cluster srcCluster,
                              Cluster destCluster,
                              HiveObjectSpec srcTableSpec,
                              List<String> partitionNames,
                              Optional<Path> commonDirectory,
                              ParallelJobExecutor copyPartitionsExecutor,
                              DirectoryCopier directoryCopier) {
        this.conf = conf;
        this.objectModifier = objectModifier;
        this.objectConflictHandler = objectConflictHandler;
        this.srcCluster = srcCluster;
        this.destCluster = destCluster;
        this.srcTableSpec = srcTableSpec;
        this.partitionNames = partitionNames;
        this.commonDirectory = commonDirectory;
        this.copyPartitionsExecutor = copyPartitionsExecutor;
        this.directoryCopier = directoryCopier;
    }

    public static Optional<Path> findCommonDirectory(HiveObjectSpec srcTableSpec,
            Map<HiveObjectSpec, Partition> specToPartition) {
        // Sanity check - verify that all the specified objects are partitions
        // and that they are from the same table

        for (HiveObjectSpec spec : specToPartition.keySet()) {

            if (!srcTableSpec.equals(spec.getTableSpec())) {
                throw new RuntimeException("Spec " + spec + " does not " +
                        "match the source table spec " + srcTableSpec);
            }

            if (!spec.isPartition()) {
                throw new RuntimeException("Partition not specified: " +
                        spec);
            }
        }

        // Collect all the partition locations
        Set<Path> partitionLocations = new HashSet<>();
        for (Map.Entry<HiveObjectSpec, Partition> entry :
                specToPartition.entrySet()) {
            partitionLocations.add(new Path(
                    entry.getValue().getSd().getLocation()));
        }
        // Find the common subdirectory among all the partitions
        // TODO: This may copy more data than necessary - use multi directory
        // copy instead once it's available.
        Optional<Path> commonDirectory = ReplicationUtils.getCommonDirectory(
                partitionLocations);
        LOG.debug("Common directory of partitions is " + commonDirectory);
        return commonDirectory;
    }

    public RunInfo runTask() throws HiveMetastoreException, DistCpException,
            IOException, HiveMetastoreException {
        LOG.debug("Copying partitions from " + srcTableSpec);
        HiveMetastoreClient destMs = destCluster.getMetastoreClient();
        HiveMetastoreClient srcMs = srcCluster.getMetastoreClient();


        // Get a fresh copy of the metadata from the source Hive metastore
        Table freshSrcTable = srcMs.getTable(srcTableSpec.getDbName(),
                srcTableSpec.getTableName());

        if (freshSrcTable == null) {
            LOG.warn("Source table " + srcTableSpec + " doesn't exist, so not " +
                    "copying");
            return new RunInfo(RunInfo.RunStatus.NOT_COMPLETABLE, 0);
        }

        if (!HiveUtils.isPartitioned(freshSrcTable)) {
            LOG.warn("Source table " + srcTableSpec
                    + " is not a a partitioned table,"
                    + " so not copying");
            return new RunInfo(RunInfo.RunStatus.NOT_COMPLETABLE, 0);
        }

        Optional<Path> tableLocation =
                ReplicationUtils.getLocation(freshSrcTable);
        LOG.debug("Location of table " + srcTableSpec + " is " + tableLocation);

        // If possible, copy the common directory in a single distcp job.
        // We call this the optimistic copy as this should result in no
        // additional distcp jobs when copying the partitions.

        long bytesCopied = 0;
        boolean doOptimisticCopy = false;

        if (commonDirectory.isPresent() &&
                tableLocation.isPresent() &&
                (tableLocation.equals(commonDirectory) ||
                        FsUtils.isSubDirectory(tableLocation.get(),
                                commonDirectory.get()))) {
            Path commonDir = commonDirectory.get();
            // Get the size of all the partitions in the common directory and
            // check if the size of the common directory is approximately
            // the same size

            long sizeOfPartitionsInCommonDirectory = 0;
            for (String partitionName : partitionNames) {
                Partition p = srcMs.getPartition(srcTableSpec.getDbName(),
                        srcTableSpec.getTableName(), partitionName);
                if (p != null && p.getSd().getLocation() != null) {
                    Path partitionLocation = new Path(p.getSd().getLocation());
                    if (FsUtils.isSubDirectory(commonDir,
                            partitionLocation) &&
                            FsUtils.dirExists(conf, partitionLocation)) {
                        sizeOfPartitionsInCommonDirectory +=
                                FsUtils.getSize(conf,
                                        partitionLocation,
                                        Optional.empty());
                    }
                }
            }

            if (!FsUtils.exceedsSize(conf, commonDir,
                    sizeOfPartitionsInCommonDirectory * 2)) {
                doOptimisticCopy = true;
            } else {
                LOG.debug(String.format("Size of common directory %s is much " +
                                "bigger than the size of the partitions in " +
                                "the common directory (%s). Hence, not " +
                                "copying the common directory", commonDir,
                        sizeOfPartitionsInCommonDirectory));
            }
        }

        Optional<Path> optimisticCopyDir = Optional.empty();
        // isPresent() isn't necessary, as doOptimisticCopy implies it's set.
        if (commonDirectory.isPresent() && doOptimisticCopy) {
            Path commonDir = commonDirectory.get();
            // Check if the common directory is the same on the destination
            String destinationLocation = objectModifier.modifyLocation(
                    srcCluster,
                    destCluster,
                    commonDir.toString());
            Path destinationLocationPath = new Path(destinationLocation);

            if (!objectModifier.shouldCopyData(destinationLocation)) {
                LOG.debug("Skipping copy of destination location " +
                        commonDirectory + " due to destination " +
                        "object factory");
            } else if (!FsUtils.dirExists(conf, commonDir)) {
                LOG.debug("Skipping copy of destination location " +
                        commonDirectory + " since it does not exist");
            } else if (FsUtils.equalDirs(conf, commonDir,
                    destinationLocationPath)) {
                LOG.debug("Skipping copying common directory " + commonDir +
                        " since it matches " + destinationLocationPath);
            } else {
                LOG.debug("Optimistically copying common directory " +
                        commonDir);
                Random random = new Random();
                long randomLong = random.nextLong();

                Path p = new PathBuilder(destCluster.getTmpDir())
                        .add("distcp_tmp")
                        .add(srcCluster.getName())
                        .add("optimistic_copy")
                        .add(Long.toString(randomLong)).toPath();
                optimisticCopyDir = Optional.of(p);

                bytesCopied += copyWithStructure(commonDir,
                        p);
            }
        }

        // Now copy all the partitions
        CopyPartitionsCounter copyPartitionsCounter =
                new CopyPartitionsCounter();
        long expectedCopyCount = 0;

        for (String partitionName : partitionNames) {
            Partition srcPartition = srcMs.getPartition(
                    srcTableSpec.getDbName(),
                    srcTableSpec.getTableName(),
                    partitionName);
            HiveObjectSpec partitionSpec = new HiveObjectSpec(
                    srcTableSpec.getDbName(),
                    srcTableSpec.getTableName(),
                    partitionName);

            if (srcPartition == null) {
                LOG.warn("Not copying missing partition: " + partitionSpec);
                continue;
            }

            CopyPartitionTask copyPartitionTask = new CopyPartitionTask(
                    conf,
                    objectModifier,
                    objectConflictHandler,
                    srcCluster,
                    destCluster,
                    partitionSpec,
                    ReplicationUtils.getLocation(srcPartition),
                    optimisticCopyDir,
                    directoryCopier,
                    true);

            CopyPartitionJob copyPartitionJob = new CopyPartitionJob(
                    copyPartitionTask,
                    copyPartitionsCounter);

            copyPartitionsExecutor.add(copyPartitionJob);
            expectedCopyCount++;
        }

        while (true) {
            LOG.debug(String.format("Copied %s out of %s partitions",
                    copyPartitionsCounter.getCompletionCount(), expectedCopyCount));

            if (copyPartitionsCounter.getCompletionCount() == expectedCopyCount) {
                break;
            }

            try {
                Thread.sleep(5 * 1000);
            } catch (InterruptedException e) {
                LOG.error("Got interrupted!");
                throw new RuntimeException(e);
            }
        }

        bytesCopied += copyPartitionsCounter.getBytesCopied();

        return new RunInfo(RunInfo.RunStatus.SUCCESSFUL, bytesCopied);
    }

    /**
     * Copies the source directory to the destination directory while preserving
     * structure. i.e. if copying /a/b/c to the destination directory /d, then
     * /d/a/b/c will be created and contain files from /a/b/c.
     *
     * @param srcPath
     * @return total number of bytes copied
     * @throws IOException
     * @throws DistCpException
     */
    private long copyWithStructure(Path srcPath, Path destDir)
            throws IOException, DistCpException {

        PathBuilder dirBuilder = new PathBuilder(destDir);
        // Preserve the directory structure within the dest directory
        // Decompose a directory like /a/b/c and add a, b, c as subdirectories
        // within the tmp direcotry
        List<String> pathElements = new ArrayList<>(
                Arrays.asList(srcPath.toUri().getPath().split("/")));
        // When splitting a path like '/a/b/c', the first element is ''
        if (pathElements.get(0).equals("")) {
            pathElements.remove(0);
        }
        for (String pathElement : pathElements) {
            dirBuilder.add(pathElement);
        }
        Path destPath = dirBuilder.toPath();

        // Copy directory
        long bytesCopied = directoryCopier.copy(
                srcPath,
                destPath,
                Arrays.asList(srcCluster.getName(), "copy_with_structure"));

        return bytesCopied;
    }

    @Override
    public LockSet getRequiredLocks() {
        LockSet lockSet = new LockSet();
        lockSet.add(new Lock(Lock.Type.SHARED, srcTableSpec.toString()));

        for (String partitionName : partitionNames) {
            HiveObjectSpec partitionSpec = new HiveObjectSpec(
                    srcTableSpec.getDbName(),
                    srcTableSpec.getTableName(),
                    partitionName);
            lockSet.add(new Lock(Lock.Type.EXCLUSIVE,
                    partitionSpec.toString()));
        }
        return lockSet;
    }

}
