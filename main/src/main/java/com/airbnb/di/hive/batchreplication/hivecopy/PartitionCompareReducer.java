package com.airbnb.di.hive.batchreplication.hivecopy;

import com.airbnb.di.hive.common.HiveMetastoreClient;
import com.airbnb.di.hive.common.HiveMetastoreException;
import com.airbnb.di.hive.common.HiveObjectSpec;
import com.airbnb.di.hive.replication.DirectoryCopier;
import com.airbnb.di.hive.replication.configuration.Cluster;
import com.airbnb.di.hive.replication.configuration.DestinationObjectFactory;
import com.airbnb.di.hive.replication.configuration.HardCodedCluster;
import com.airbnb.di.hive.replication.deploy.ConfigurationException;
import com.airbnb.di.hive.replication.deploy.DeployConfigurationKeys;
import com.airbnb.di.hive.replication.primitives.TaskEstimate;
import com.airbnb.di.hive.replication.primitives.TaskEstimator;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.IOException;
import java.net.URI;

import static com.airbnb.di.hive.batchreplication.hivecopy.MetastoreReplicationJob.deseralizeJobResult;
import static com.airbnb.di.hive.batchreplication.hivecopy.MetastoreReplicationJob.serializeJobResult;
import static com.airbnb.di.hive.replication.deploy.ReplicationLauncher.makeURI;

/**
 * Reducer to compare partition entity
 */
public class PartitionCompareReducer extends Reducer<LongWritable, Text, Text, Text> {
    private static final Log LOG = LogFactory.getLog(PartitionCompareReducer.class);

    private static final DestinationObjectFactory destinationObjectFactory = new DestinationObjectFactory();

    private Configuration conf;
    private HiveMetastoreClient srcClient;
    private HiveMetastoreClient dstClient;
    private Cluster srcCluster;
    private Cluster dstCluster;
    private DirectoryCopier directoryCopier;
    private long count = 0;
    private TaskEstimator estimator;

    public PartitionCompareReducer() {
    }

    protected void setup(Context context) throws IOException,
            InterruptedException {
        try {
            this.conf = context.getConfiguration();
            // Create the source cluster object
            String srcClusterName = conf.get(
                    DeployConfigurationKeys.SRC_CLUSTER_NAME);
            String srcMetastoreUrlString = conf.get(
                    DeployConfigurationKeys.SRC_CLUSTER_METASTORE_URL);
            URI srcMetastoreUrl = makeURI(srcMetastoreUrlString);
            String srcHdfsRoot = conf.get(
                    DeployConfigurationKeys.SRC_HDFS_ROOT);
            String srcHdfsTmp = conf.get(
                    DeployConfigurationKeys.SRC_HDFS_TMP);
            this.srcCluster = new HardCodedCluster(
                    srcClusterName,
                    srcMetastoreUrl.getHost(),
                    srcMetastoreUrl.getPort(),
                    null,
                    null,
                    new Path(srcHdfsRoot),
                    new Path(srcHdfsTmp));
            this.srcClient = this.srcCluster.getMetastoreClient();

            // Create the dest cluster object
            String destClusterName = conf.get(
                    DeployConfigurationKeys.DEST_CLUSTER_NAME);
            String destMetastoreUrlString = conf.get(
                    DeployConfigurationKeys.DEST_CLUSTER_METASTORE_URL);
            URI destMetastoreUrl = makeURI(destMetastoreUrlString);
            String destHdfsRoot = conf.get(
                    DeployConfigurationKeys.DEST_HDFS_ROOT);
            String destHdfsTmp = conf.get(
                    DeployConfigurationKeys.DEST_HDFS_TMP);
            this.dstCluster = new HardCodedCluster(
                    destClusterName,
                    destMetastoreUrl.getHost(),
                    destMetastoreUrl.getPort(),
                    null,
                    null,
                    new Path(destHdfsRoot),
                    new Path(destHdfsTmp));
            this.dstClient = this.dstCluster.getMetastoreClient();
            this.directoryCopier = new DirectoryCopier(conf, srcCluster.getTmpDir(), false);
            this.estimator = new TaskEstimator(conf,
                    destinationObjectFactory,
                    srcCluster,
                    dstCluster,
                    directoryCopier);
        } catch (HiveMetastoreException | ConfigurationException e) {
            throw new IOException(e);
        }
    }

    protected void reduce(LongWritable key, Iterable<Text> values, Context context)
            throws IOException, InterruptedException {

        for (Text value : values) {
            Pair<TaskEstimate, HiveObjectSpec> input = deseralizeJobResult(value.toString());
            TaskEstimate estimate = input.getLeft();
            HiveObjectSpec spec = input.getRight();
            String result = null;

            try {
                if (estimate.getTaskType() == TaskEstimate.TaskType.CHECK_PARTITION) {
                    // Table exists in source, but not in dest. It should copy the table.
                    TaskEstimate newEstimate = estimator.analyze(spec);

                    result = serializeJobResult(newEstimate, spec);
                }
            } catch (HiveMetastoreException e) {
                LOG.info(String.format("Hit exception during db:%s, tbl:%s, part:%s", spec.getDbName(), spec.getTableName(), spec.getPartitionName()));
                result = String.format("exception in %s of mapper = %s", estimate.getTaskType().toString(),
                        context.getTaskAttemptID().toString());
                LOG.info(e.getMessage());
            }

            context.write(value, new Text(result));
            ++this.count;
            if (this.count % 100 == 0) {
                LOG.info("Processed " + this.count + "entities");
            }
        }
    }

    protected void cleanup(Context context) throws IOException,
            InterruptedException {
        this.srcClient.close();
        this.dstClient.close();
    }
}
