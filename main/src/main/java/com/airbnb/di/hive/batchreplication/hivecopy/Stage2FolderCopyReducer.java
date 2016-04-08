package com.airbnb.di.hive.batchreplication.hivecopy;

import com.airbnb.di.hive.batchreplication.SimpleFileStatus;
import com.airbnb.di.hive.replication.ReplicationUtils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.IOException;

/**
 * Stage 2 reducer to handle folder copy.
 *
 * <p>Input is the files needs to be copied. Load balance is done through shuffle. Output of the job
 * is file copied or skipped.
 */
public class Stage2FolderCopyReducer extends Reducer<LongWritable, Text, Text, Text> {
  private static final Log LOG = LogFactory.getLog(Stage2FolderCopyReducer.class);
  private Configuration conf;

  enum CopyStatus {
    COPIED,
    SKIPPED
  }

  public Stage2FolderCopyReducer() {
  }

  protected void setup(Context context) throws IOException, InterruptedException {
    this.conf = context.getConfiguration();
  }

  protected void reduce(LongWritable key, Iterable<Text> values, Context context)
    throws IOException, InterruptedException {
    for (Text value : values) {
      String[] fields = value.toString().split("\t");
      String srcFileName = fields[0];
      String dstFolder = fields[1];
      long size = Long.valueOf(fields[2]);
      SimpleFileStatus fileStatus = new SimpleFileStatus(srcFileName, size, 0L);
      FileSystem srcFs = (new Path(srcFileName)).getFileSystem(this.conf);
      FileSystem dstFs = (new Path(dstFolder)).getFileSystem(this.conf);
      String result = ReplicationUtils.doCopyFileAction(
          conf,
          fileStatus,
          srcFs,
          dstFolder,
          dstFs,
          context,
          false,
          context.getTaskAttemptID().toString());
      if (result == null) {
        context.write(new Text(CopyStatus.COPIED.toString()),
            new Text(ReplicationUtils.genValue(value.toString(), " ",
                String.valueOf(System.currentTimeMillis()))));
      } else {
        context.write(
            new Text(CopyStatus.SKIPPED.toString()),
            new Text(ReplicationUtils.genValue(
                value.toString(),
                result,
                String.valueOf(System.currentTimeMillis()))));
      }
    }
  }
}
