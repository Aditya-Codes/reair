package com.airbnb.di.hive.batchreplication.hdfscopy;

import com.airbnb.di.hive.batchreplication.ExtendedFileStatus;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.hash.*;
import org.apache.commons.cli.*;
import org.apache.commons.cli.Options;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.*;

/**
 *
 * A Map/Reduce job that takes in gzipped json log file (like the kind that
 * are dumped out by flog), splits them based on the key data.event_name
 * and writes out the results to gzipped files split on event name.
 */
public class ReplicationJob extends Configured implements Tool {
    private static final Log LOG = LogFactory.getLog(ReplicationJob.class);
    private static final String SRC_HOSTNAME_CONF = "replication.src.hostname";
    private static final String DST_HOSTNAME_CONF = "replication.dst.hostname";
    private static final String COMPARE_OPTION_CONF = "replication.compare.option";
    public static final String DIRECTORY_BLACKLIST_REGEX = "replication.folder.blacklist";

    private static final PathFilter hiddenFileFilter = new PathFilter() {
        public boolean accept(Path p) {
            String name = p.getName();
            return !name.startsWith("_") && !name.startsWith(".");
        }
    };

    private static String getHostName(String path) {
        String[] parts = path.split("/");
        assert parts.length > 3;

        // return host name
        return parts[2];
    }

    public static class ListFileMapper extends Mapper<Text, Boolean, Text, FileStatus> {
        private String folderBlackList;

        private static String getPathNoHostName(String path) {
            String[] parts = path.split("/");
            assert parts.length > 3;

            return "/" + Joiner.on("/").join(Arrays.copyOfRange(parts, 3, parts.length));
        }

        private void enumDirectories(FileSystem fs, Path root, boolean recursive, Mapper.Context context) throws IOException {
            try {
                for (FileStatus status : fs.listStatus(root, hiddenFileFilter)) {
                    if (status.isDir()) {
                        if (recursive) {
                            if (folderBlackList == null || !status.getPath().getName().matches(folderBlackList)) {
                                enumDirectories(fs, status.getPath(), recursive, context);
                            }
                        }
                    } else {
                        try {
                            context.write(new Text(getPathNoHostName(root.toString())), status);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
                context.progress();
            } catch (FileNotFoundException e) {
                return;
            }
        }

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            this.folderBlackList = context.getConfiguration().get(DIRECTORY_BLACKLIST_REGEX);
        }

        @Override
        protected void map(Text key, Boolean value, Context context) throws IOException, InterruptedException {
            Path folder = new Path(key.toString());
            FileSystem fileSystem = folder.getFileSystem(context.getConfiguration());
            enumDirectories(fileSystem, folder, value, context);
            LOG.info(key.toString() + " processed.");
        }
    }

    /**
     * Generate hdfs file statistics table for hive.
     */
    public static class FolderSizeReducer extends Reducer<Text, FileStatus, Text, Text> {
        private static String produceHdfsStats(FileStatus fileStatus) {
            ArrayList<String> fields = new ArrayList<>();

            String[] parts = fileStatus.getPath().toString().split("/");
            assert parts.length > 3;

            // add host name
            fields.add(parts[2]);

            // add relative path
            fields.add("/" + Joiner.on("/").join(Arrays.copyOfRange(parts, 3, parts.length)));

            // add level up to 10
            fields.addAll(Arrays.asList(Arrays.copyOfRange(parts, 3, 13)));

            // add file size
            fields.add(String.valueOf(fileStatus.getLen()));

            // add block size
            fields.add(String.valueOf(fileStatus.getBlockSize()));

            // add owner
            fields.add(String.valueOf(fileStatus.getOwner()));

            // add group
            fields.add(String.valueOf(fileStatus.getGroup()));

            // add permission
            fields.add(String.valueOf(fileStatus.getModificationTime()));

            // add EveryThing
            fields.add(fileStatus.toString());

            return Joiner.on("\t").useForNull("\\N").join(fields);
        }

        @Override
        protected void reduce(Text key, Iterable<FileStatus> values, Context context)
                throws IOException, InterruptedException {
            for(FileStatus fs: values) {
                context.write(new Text(fs.getPath().toString()), new Text(produceHdfsStats(fs)));
            }
        }
    }

    private static Text generateValue(String action, ExtendedFileStatus fileStatus) {
        ArrayList<String> fields = new ArrayList<>();

        fields.add(action);
        fields.add(fileStatus.getFullPath());
        fields.add(String.valueOf(fileStatus.getFileSize()));
        fields.add(String.valueOf(fileStatus.getModificationTime()));

        return new Text(Joiner.on("\t").useForNull("\\N").join(fields));
    }

    /**
     * Compare source1 + source2 with destination
     */
    public static class FolderCompareReducer extends  Reducer<Text, FileStatus, Text, Text> {
        private String dstHost;
        private Predicate<ExtendedFileStatus> dstHostPred;
        private HashSet<String> compareOption;

        private ExtendedFileStatus findSrcFileStatus(List<ExtendedFileStatus> fileStatuses) {
            // pick copy source. The source is the one with largest timestamp value
            return Ordering.from(new Comparator<ExtendedFileStatus>() {
                @Override
                public int compare(ExtendedFileStatus o1, ExtendedFileStatus o2) {
                    return Long.compare(o1.getModificationTime(), o2.getModificationTime());
                }
            }).max(fileStatuses);
        }

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            super.setup(context);
            this.dstHost = context.getConfiguration().get(DST_HOSTNAME_CONF);
            this.dstHostPred = new Predicate<ExtendedFileStatus>() {
                @Override
                public boolean apply(@Nullable ExtendedFileStatus extendedFileStatus) {
                    return extendedFileStatus.getHostName().equals(dstHost);
                }
            };
            this.compareOption = new HashSet<>();
            this.compareOption.addAll(Arrays.asList(context.getConfiguration().get(COMPARE_OPTION_CONF, "a,d,u").split(",")));
        }

        @Override
        protected void reduce(Text key, Iterable<FileStatus> values, Context context)
                throws IOException, InterruptedException {
            ListMultimap<String, ExtendedFileStatus> fileStatusHashMap = LinkedListMultimap.create();

            for(FileStatus fs: values) {
                ExtendedFileStatus efs = new ExtendedFileStatus(fs.getPath().toString(), fs.getLen(), fs.getModificationTime());
                fileStatusHashMap.put(efs.getPath(), efs);
            }

            for (String relativePath : fileStatusHashMap.keySet()) {
                List<ExtendedFileStatus> fileStatuses = fileStatusHashMap.get(relativePath);
                ArrayList<ExtendedFileStatus> srcFileStatus = Lists.newArrayList(Iterables.filter(fileStatuses, Predicates.not(this.dstHostPred)));
                ArrayList<ExtendedFileStatus> dstFileStatus = Lists.newArrayList(Iterables.filter(fileStatuses, this.dstHostPred));

                if (dstFileStatus.size() > 0) {
                    // we can only have one destination
                    assert dstFileStatus.size() == 1;

                    // if destination has file, there are two cases:
                    if (srcFileStatus.size() > 0) {
                        // pick copy source first. The source is the one with largest timestamp value
                        ExtendedFileStatus finalSrcFileStatus = findSrcFileStatus(srcFileStatus);

                        // if file size is
                        if (finalSrcFileStatus.getFileSize() != dstFileStatus.get(0).getFileSize()) {
                            if (compareOption.contains("u")) {
                                // if file size is different then we need update.
                                context.write(new Text(relativePath), generateValue("update", finalSrcFileStatus));
                            }
                        }
                    } else {
                        if (compareOption.contains("d")) {
                            //  2. source does not exist it is delete
                            context.write(new Text(relativePath), generateValue("delete", dstFileStatus.get(0)));
                        }
                    }
                } else {
                    if (compareOption.contains("a")) {
                        // if no destination, then this is a new file.
                        ExtendedFileStatus src = findSrcFileStatus(srcFileStatus);
                        context.write(new Text(relativePath), generateValue("add", src));
                    }
                }
            }
        }
    }

    // Mapper to rebalance files need to be copied.
    public static class HdfsSyncMapper extends Mapper<LongWritable, Text, LongWritable, Text> {
        @Override
        protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
            String[] fields = value.toString().split("\t");
            long hashValue = Hashing.murmur3_128().hashLong(Long.valueOf(fields[3]).hashCode() * Long.valueOf(fields[4]).hashCode()).asLong();
            context.write(new LongWritable(hashValue), value);
        }
    }

    public static class HdfsSyncReducer extends Reducer<LongWritable, Text, Text, Text> {
        private HashMap<String, FileSystem> fileSystemHashMap;
        private String[] sourceHosts;
        private String dstHost;
        private long copiedSize = 0;

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            super.setup(context);
            this.fileSystemHashMap = new HashMap<>();
            this.sourceHosts = context.getConfiguration().get(SRC_HOSTNAME_CONF).split(",");
            this.dstHost = context.getConfiguration().get(DST_HOSTNAME_CONF);
            for (String src : sourceHosts) {
                Path srcPath = new Path("hdfs://" + src + "/");
                this.fileSystemHashMap.put(src, srcPath.getFileSystem(context.getConfiguration()));
            }
            this.fileSystemHashMap.put(dstHost,
                    new Path("hdfs://" + dstHost + "/").getFileSystem(context.getConfiguration()));
        }

        private void copyFile(FSDataInputStream inputStream, FSDataOutputStream outputStream, Context context) throws IOException {
            byte[] buffer = new byte[4*1024*1024];
            int bytesRead;
            int it = 0;
            while ((bytesRead = inputStream.read(buffer)) > 0) {
                it++;
                outputStream.write(buffer, 0, bytesRead);
                copiedSize += bytesRead;
                if (it % 1000 == 0) {
                    context.progress();
                }
            }
        }

        private boolean doCopyFileAction(ExtendedFileStatus srcFileStatus, String dstFolderPath, Context context, boolean update) {
            try {
                // check src existance
                FileSystem srcFs = fileSystemHashMap.get(srcFileStatus.getHostName());
                Path srcPath = new Path(srcFileStatus.getFullPath());
                if (!srcFs.exists(srcPath)) {
                    //src file does not exist anymore, skip copy
                    LOG.info("Src does not exist. " + srcFileStatus.getFullPath());
                    return false;
                }

                FSDataInputStream inputStream = srcFs.open(srcPath);

                // check dst existance
                Path dstPath = new Path("hdfs://" + dstHost + "/" + dstFolderPath + "/" + srcFileStatus.getFileName());
                FileSystem dstFs = fileSystemHashMap.get(dstHost);
                if (dstFs.exists(dstPath) && !update) {
                    //if dst file already exist and it is not update action, skip copy
                    LOG.info("dst already exists. " + dstPath.toString());
                    return false;
                }

                // make sure dstFolder created
                Path dstParentPath = new Path(dstFolderPath);
                if (!dstFs.exists(dstParentPath)) {
                    if(!dstFs.mkdirs(dstParentPath)) {
                        LOG.info("Could not create directory: " + dstFolderPath);
                        return false;
                    }
                }

                // copy to a tmp file
                Path tmpDstPath = new Path("hdfs://" + dstHost + "/" + dstFolderPath + "/__tmp__copy__file." + System.currentTimeMillis());
                if (dstFs.exists(tmpDstPath)) {
                    dstFs.delete(tmpDstPath, false);
                }

                FSDataOutputStream outputStream = dstFs.create(tmpDstPath);
                copyFile(inputStream, outputStream, context);
                inputStream.close();
                outputStream.close();

                // for update if dstPath exist, we need to remove it.
                if (update && dstFs.exists(dstPath)) {
                    dstFs.delete(dstPath, false);
                }

                // if dst exist, rename will fail so we will skip.
                dstFs.rename(tmpDstPath, dstPath);

                // set proper file properties, owner, group, last modified time
                FileStatus srcStatus = srcFs.getFileStatus(srcPath);
                //dstFs.setOwner(dstPath, srcStatus.getOwner(), srcStatus.getGroup());
                dstFs.setTimes(dstPath, srcStatus.getModificationTime(), srcStatus.getAccessTime());

                LOG.info(dstPath.toString() + " file copied");
                context.progress();

            } catch (IOException e) {
                e.printStackTrace();
                LOG.info(e.getMessage());
                return false;
            }
            return true;
        }

        @Override
        protected void reduce(LongWritable key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
            for(Text value : values) {
                String[] fields = value.toString().split("\t");

                // We only support add operation for now.
                if (fields[1].equals("add") || fields[1].equals("update")) {
                    ExtendedFileStatus fileStatus = new ExtendedFileStatus(fields[2], Long.valueOf(fields[3]), Long.valueOf(fields[4]));
                    Path dstFile = new Path(fields[0]);

                    if (doCopyFileAction(fileStatus, dstFile.getParent().toString(), context, fields[1].equals("update"))) {
                        context.write(new Text(fields[0]), generateValue("copied", fileStatus));
                    } else {
                        context.write(new Text(fields[0]), generateValue("skip copy", fileStatus));
                    }
                }
            }
        }

        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            LOG.info("Total bytes copied = " + copiedSize);
        }
    }

    /**
     * Print usage information to provided OutputStream.
     *
     * @param applicationName Name of application to list in usage.
     * @param options Command-line options to be part of usage.
     * @param out OutputStream to which to write the usage information.
     */
    public static void printUsage(
            final String applicationName,
            final Options options,
            final OutputStream out) {
        final PrintWriter writer = new PrintWriter(out);
        final HelpFormatter usageFormatter = new HelpFormatter();
        usageFormatter.printUsage(writer, 80, applicationName, options);
        writer.flush();
    }

    /**
     * Construct and provide GNU-compatible Options.
     *
     * @return Options expected from command-line of GNU form.
     */
    public static Options constructGnuOptions()
    {
        final Options gnuOptions = new Options();
        gnuOptions.addOption("s", "source", true, "source folders").
                addOption("d", "destination", true, "destination folder").
                addOption("o", "output", true, "output folder").
                addOption("p", "option", true, "checking options: comma seperated option including a(add),d(delete),u(update)").
                addOption("l", "list", false, "list file size only").
                addOption("b", "blacklist", true, "folder blacklist regex").
                addOption("dry", "dryrun", false, "dryrun only");
        return gnuOptions;
    }

    public int run(String[] args) throws Exception {
        final CommandLineParser cmdLineGnuParser = new GnuParser();

        final Options gnuOptions = constructGnuOptions();
        CommandLine commandLine;
        try
        {
            commandLine = cmdLineGnuParser.parse(gnuOptions, args);
        }
        catch (ParseException parseException)  // checked exception
        {
            System.err.println(
                    "Encountered exception while parsing using GnuParser:\n"
                            + parseException.getMessage() );
            System.err.println("Usage: hadoop jar ReplicationJob-0.1.jar <in> <out>");
            System.out.println();
            ToolRunner.printGenericCommandUsage(System.err);
            return 1;
        }

        if (commandLine.hasOption("l")) {
            if (!commandLine.hasOption("s")) {
                printUsage("Usage: hadoop jar ReplicationJob-0.1.jar", constructGnuOptions(), System.out);
                return 1;
            }
        } else {
            if (!commandLine.hasOption("s") || !commandLine.hasOption("d")) {
                printUsage("Usage: hadoop jar ReplicationJob-0.1.jar", constructGnuOptions(), System.out);
                return 1;
            }
        }

        if (!commandLine.hasOption("o")) {
            printUsage("Usage: hadoop jar ReplicationJob-0.1.jar", constructGnuOptions(), System.out);
            return 1;
        }

        if (commandLine.hasOption("b")) {
            getConf().set(DIRECTORY_BLACKLIST_REGEX, commandLine.getOptionValue("b"));
            LOG.info("Blacklist:" + commandLine.getOptionValue("b") );
        }

        if (commandLine.hasOption("l")) {
            return runHdfsStatsJob(commandLine.getOptionValue("s"), commandLine.getOptionValue("o"));
        } else {
            if (commandLine.hasOption("dry")) {
                return runReplicationCompareJob(commandLine.getOptionValue("s"), commandLine.getOptionValue("d"),
                        commandLine.getOptionValue("o"), commandLine.getOptionValue("p"));
            } else {
                Path outputRoot = new Path(commandLine.getOptionValue("o")).getParent();
                String tmpPath = outputRoot.toString() + "/__tmp_hive_result_." + System.currentTimeMillis();
                int retVal = 1;

                if (runReplicationCompareJob(commandLine.getOptionValue("s"), commandLine.getOptionValue("d"),
                    tmpPath, commandLine.getOptionValue("p")) == 0) {
                    Path tmpFolder = new Path(tmpPath);
                    FileSystem fs = tmpFolder.getFileSystem(getConf());
                    if (!fs.exists(tmpFolder)) {
                        LOG.error(tmpFolder.toString() + " folder does not exist");
                        return 1;
                    }
                    LOG.info("output exists: " + fs.getFileStatus(tmpFolder).toString());
                    retVal = runSyncJob(commandLine.getOptionValue("s"), commandLine.getOptionValue("d"),
                            tmpPath + "/part*", commandLine.getOptionValue("o"));
                }

                Path tmpFolder = new Path(tmpPath);
                FileSystem fs = tmpFolder.getFileSystem(getConf());
                if (fs.exists(tmpFolder)) {
                    fs.delete(tmpFolder, true);
                }
                return retVal;
            }
        }
    }

    private int runHdfsStatsJob(String source, String output)
            throws IOException, InterruptedException, ClassNotFoundException {
        Job job = new Job(getConf(), "HDFS stats job");
        job.setJarByClass(getClass());

        job.setInputFormatClass(DirScanInputFormat.class);
        job.setMapperClass(ListFileMapper.class);

        job.setReducerClass(FolderSizeReducer.class);
        job.getConfiguration().set("mapred.input.dir", source );

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(FileStatus.class);

        FileOutputFormat.setOutputPath(job, new Path(output));
        FileOutputFormat.setOutputCompressorClass(job, GzipCodec.class);

        boolean success = job.waitForCompletion(true);

        return success ? 0 : 1;
    }

    private static String getSourceHosts(String source) {
        String[] srcfolders = source.split(",");

        return Joiner.on(",").join(
                Lists.transform(Arrays.asList(srcfolders),
                        new Function<String, String>() {
                            @Override
                            public String apply(String s) {
                                return getHostName(s);
                            }
                        }));
    }

    private int runReplicationCompareJob(String source, String destination, String output, String compareOption)
            throws IOException, InterruptedException, ClassNotFoundException {
        Job job = new Job(getConf(), "Replication Compare job");
        job.setJarByClass(getClass());

        job.setInputFormatClass(DirScanInputFormat.class);
        job.setMapperClass(ListFileMapper.class);

        job.setReducerClass(FolderCompareReducer.class);

        //last folder is destination, all other folders are source folder
        job.getConfiguration().set(SRC_HOSTNAME_CONF, getSourceHosts(source));
        job.getConfiguration().set(DST_HOSTNAME_CONF, getHostName(destination));
        job.getConfiguration().set("mapred.input.dir", Joiner.on(",").join(source, destination));
        job.getConfiguration().set(COMPARE_OPTION_CONF, compareOption);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(FileStatus.class);

        FileOutputFormat.setOutputPath(job, new Path(output));
        FileOutputFormat.setOutputCompressorClass(job, GzipCodec.class);

        boolean success = job.waitForCompletion(true);

        return success ? 0 : 1;
    }

    private int runSyncJob(String source, String destination, String input, String output)
            throws IOException, InterruptedException, ClassNotFoundException {
        Job job = new Job(getConf(), "HDFS Sync job");
        job.setJarByClass(getClass());

        job.setInputFormatClass(TextInputFormat.class);
        job.setMapperClass(HdfsSyncMapper.class);
        job.setReducerClass(HdfsSyncReducer.class);

        job.setOutputKeyClass(LongWritable.class);
        job.setOutputValueClass(Text.class);

        job.getConfiguration().set(SRC_HOSTNAME_CONF, getSourceHosts(source));
        job.getConfiguration().set(DST_HOSTNAME_CONF, getHostName(destination));

        FileInputFormat.setInputPaths(job, new Path(input));
        FileInputFormat.setMaxInputSplitSize(job, 60000);
        FileOutputFormat.setOutputPath(job, new Path(output));
        FileOutputFormat.setOutputCompressorClass(job, GzipCodec.class);

        boolean success = job.waitForCompletion(true);

        return success ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new ReplicationJob(), args);
        System.exit(res);
    }
}
