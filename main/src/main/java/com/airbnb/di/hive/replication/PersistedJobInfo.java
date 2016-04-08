package com.airbnb.di.hive.replication;

import com.airbnb.di.hive.replication.primitives.ReplicationTask;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A replication job copies either: HDFS directory, Hive table, or a Hive partition.
 */
public class PersistedJobInfo {

  private static final Log LOG = LogFactory.getLog(PersistedJobInfo.class);

  private Long id;
  private long createTime;
  private ReplicationOperation operation;
  private ReplicationStatus status;

  // Path of the source may not exist for views
  private Optional<Path> srcPath;
  private String srcClusterName;
  private String srcDbName;
  private String srcTableName;

  // If copying partition(s), the partition names
  private List<String> srcPartitionNames;

  // The modified time of the source object - from the field in parameters,
  // transientLast_ddlTime. This field is only applicable for rename and
  // drop operations.
  private Optional<String> srcObjectTldt;

  // These fields are only applicable for the rename operation.
  private Optional<String> renameToDb;
  private Optional<String> renameToTable;
  private Optional<String> renameToPartition;
  private Optional<Path> renameToPath;

  // A flexible map to store some extra parameters
  private Map<String, String> extras;

  public static final String AUDIT_LOG_ID_EXTRAS_KEY = "audit_log_id";
  public static final String AUDIT_LOG_ENTRY_CREATE_TIME_KEY = "audit_log_entry_create_time";
  public static final String BYTES_COPIED_KEY = "bytes_copied";

  public PersistedJobInfo() {

  }

  public PersistedJobInfo(
      Long id,
      Long createTime,
      ReplicationOperation operation,
      ReplicationStatus status,
      Optional<Path> srcPath,
      String srcClusterName,
      String srcDbName,
      String srcTableName,
      List<String> srcPartitionNames,
      Optional<String> srcObjectTldt,
      Optional<String> renameToDb,
      Optional<String> renameToTable,
      Optional<String> renameToPartition,
      Optional<Path> renameToPath,
      Map<String, String> extras) {
    this.id = id;
    this.createTime = createTime;
    this.operation = operation;
    this.status = status;
    this.srcPath = srcPath;
    this.srcClusterName = srcClusterName;
    this.srcDbName = srcDbName;
    this.srcTableName = srcTableName;
    if (srcPartitionNames != null) {
      this.srcPartitionNames = srcPartitionNames;
    } else {
      LOG.error("null srcPartitionNames passed in constructor", new Exception());
      this.srcPartitionNames = new ArrayList<>();
    }
    this.srcObjectTldt = srcObjectTldt;
    this.renameToDb = renameToDb;
    this.renameToTable = renameToTable;
    this.renameToPartition = renameToPartition;
    this.renameToPath = renameToPath;
    if (extras == null) {
      LOG.error("null extras passed in constructor", new Exception());
      this.extras = new HashMap<>();
    } else {
      this.extras = extras;
    }
  }

  public void copy(PersistedJobInfo o) {
    this.id = o.id;
    this.createTime = o.createTime;
    this.operation = o.operation;
    this.status = o.status;
    this.srcPath = o.srcPath;
    this.srcClusterName = o.srcClusterName;
    this.srcDbName = o.srcDbName;
    this.srcTableName = o.srcDbName;
    this.srcPartitionNames = o.srcPartitionNames;
    this.srcObjectTldt = o.srcObjectTldt;
    this.renameToDb = o.renameToDb;
    this.renameToTable = o.renameToTable;
    this.renameToPartition = o.renameToPartition;
    this.renameToPath = o.renameToPath;
    this.extras = extras == null ? null : new HashMap<>(extras);
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getId() {
    return id;
  }

  public ReplicationOperation getOperation() {
    return operation;
  }

  public ReplicationStatus getStatus() {
    return status;
  }

  public String getSrcClusterName() {
    return srcClusterName;
  }

  public Optional<Path> getSrcPath() {
    return srcPath;
  }

  public String getSrcDbName() {
    return srcDbName;
  }

  public String getSrcTableName() {
    return srcTableName;
  }

  public List<String> getSrcPartitionNames() {
    return srcPartitionNames;
  }

  public Optional<String> getSrcObjectTldt() {
    return srcObjectTldt;
  }

  public void setStatus(ReplicationStatus status) {
    this.status = status;
  }

  public Optional<String> getRenameToDb() {
    return renameToDb;
  }

  public Optional<String> getRenameToTable() {
    return renameToTable;
  }

  public Optional<String> getRenameToPartition() {
    return renameToPartition;
  }

  public Optional<Path> getRenameToPath() {
    return renameToPath;
  }

  public Map<String, String> getExtras() {
    return extras;
  }

  public long getCreateTime() {
    return createTime;
  }

  @Override
  public String toString() {
    return "PersistedJobInfo{" + "id=" + id + ", operation=" + operation + ", createTime="
        + createTime + ", status=" + status + ", srcPath=" + srcPath + ", srcClusterName='"
        + srcClusterName + '\'' + ", srcDbName='" + srcDbName + '\'' + ", srcTableName='"
        + srcTableName + '\'' + ", srcPartitionNames=" + srcPartitionNames + ", srcObjectTldt='"
        + srcObjectTldt + '\'' + ", renameToDb='" + renameToDb + '\'' + ", renameToTable='"
        + renameToTable + '\'' + ", renameToPartition='" + renameToPartition + '\''
        + ", renameToPath=" + renameToPath + ", extras=" + extras + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    PersistedJobInfo that = (PersistedJobInfo) o;

    if (createTime != that.createTime)
      return false;
    if (extras != null ? !extras.equals(that.extras) : that.extras != null)
      return false;
    if (id != null ? !id.equals(that.id) : that.id != null)
      return false;
    if (operation != that.operation)
      return false;
    if (renameToDb != null ? !renameToDb.equals(that.renameToDb) : that.renameToDb != null)
      return false;
    if (renameToPartition != null ? !renameToPartition.equals(that.renameToPartition)
        : that.renameToPartition != null)
      return false;
    if (renameToPath != null ? !renameToPath.equals(that.renameToPath) : that.renameToPath != null)
      return false;
    if (renameToTable != null ? !renameToTable.equals(that.renameToTable)
        : that.renameToTable != null)
      return false;
    if (srcClusterName != null ? !srcClusterName.equals(that.srcClusterName)
        : that.srcClusterName != null)
      return false;
    if (srcDbName != null ? !srcDbName.equals(that.srcDbName) : that.srcDbName != null)
      return false;
    if (srcObjectTldt != null ? !srcObjectTldt.equals(that.srcObjectTldt)
        : that.srcObjectTldt != null)
      return false;
    if (srcPartitionNames != null ? !srcPartitionNames.equals(that.srcPartitionNames)
        : that.srcPartitionNames != null)
      return false;
    if (srcPath != null ? !srcPath.equals(that.srcPath) : that.srcPath != null)
      return false;
    if (srcTableName != null ? !srcTableName.equals(that.srcTableName) : that.srcTableName != null)
      return false;
    if (status != that.status)
      return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = id != null ? id.hashCode() : 0;
    result = 31 * result + (int) (createTime ^ (createTime >>> 32));
    result = 31 * result + (operation != null ? operation.hashCode() : 0);
    result = 31 * result + (status != null ? status.hashCode() : 0);
    result = 31 * result + (srcPath != null ? srcPath.hashCode() : 0);
    result = 31 * result + (srcClusterName != null ? srcClusterName.hashCode() : 0);
    result = 31 * result + (srcDbName != null ? srcDbName.hashCode() : 0);
    result = 31 * result + (srcTableName != null ? srcTableName.hashCode() : 0);
    result = 31 * result + (srcPartitionNames != null ? srcPartitionNames.hashCode() : 0);
    result = 31 * result + (srcObjectTldt != null ? srcObjectTldt.hashCode() : 0);
    result = 31 * result + (renameToDb != null ? renameToDb.hashCode() : 0);
    result = 31 * result + (renameToTable != null ? renameToTable.hashCode() : 0);
    result = 31 * result + (renameToPartition != null ? renameToPartition.hashCode() : 0);
    result = 31 * result + (renameToPath != null ? renameToPath.hashCode() : 0);
    result = 31 * result + (extras != null ? extras.hashCode() : 0);
    return result;
  }
}
