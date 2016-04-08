package com.airbnb.di.hive.replication.auditlog;

import com.airbnb.di.hive.common.HiveObjectSpec;
import com.airbnb.di.hive.common.NamedPartition;
import com.airbnb.di.hive.hooks.HiveOperation;
import org.apache.hadoop.hive.metastore.api.Table;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class AuditLogEntry {

  // The audit log has more fields, but only these are relevant for
  // replication.
  private long id;
  private Timestamp createTime;
  private String command;
  private HiveOperation commandType;
  private List<String> outputDirectories;
  private List<Table> referenceTables;
  private List<Table> outputTables;
  private List<NamedPartition> outputPartitions;
  private Table renameFromTable;
  private NamedPartition renameFromPartition;

  public AuditLogEntry() {

  }

  /**
   * TODO.
   *
   * @param id TODO
   * @param createTime TODO
   * @param commandType TODO
   * @param command TODO
   * @param outputDirectories TODO
   * @param referenceTables TODO
   * @param outputTables TODO
   * @param outputPartitions TODO
   * @param renameFromTable TODO
   * @param renameFromPartition TODO
   */
  public AuditLogEntry(
      long id,
      Timestamp createTime,
      HiveOperation commandType,
      String command,
      List<String> outputDirectories,
      List<Table> referenceTables,
      List<Table> outputTables,
      List<NamedPartition> outputPartitions,
      Table renameFromTable,
      NamedPartition renameFromPartition) {
    this.id = id;
    this.createTime = createTime;
    this.commandType = commandType;
    this.command = command;
    this.referenceTables = referenceTables;
    this.outputDirectories = outputDirectories;
    this.outputTables = outputTables;
    this.outputPartitions = outputPartitions;
    this.renameFromTable = renameFromTable;
    this.renameFromPartition = renameFromPartition;
  }

  public long getId() {
    return id;
  }

  public Timestamp getCreateTime() {
    return createTime;
  }

  public HiveOperation getCommandType() {
    return commandType;
  }

  /**
   * TODO.
   */
  public String toString() {

    List<String> outputTableStrings = new ArrayList<>();
    for (Table table : outputTables) {
      outputTableStrings.add(new HiveObjectSpec(table).toString());
    }
    List<String> outputPartitionStrings = new ArrayList<>();
    for (NamedPartition pwn : outputPartitions) {
      outputPartitionStrings.add(new HiveObjectSpec(pwn).toString());
    }

    List<String> referenceTableStrings = new ArrayList<>();
    for (Table t : referenceTables) {
      referenceTableStrings.add(new HiveObjectSpec(t).toString());
    }
    return "AuditLogEntry{" + "id=" + id + ", createTime=" + createTime + ", commandType="
        + commandType + ", outputDirectories=" + outputDirectories + ", referenceTables="
        + referenceTableStrings + ", outputTables=" + outputTableStrings + ", outputPartitions="
        + outputPartitionStrings + ", renameFromTable=" + renameFromTable + ", renameFromPartition="
        + renameFromPartition + '}';
  }

  /**
   * TODO.
   *
   * @return TODO
   */
  public String toDetailedString() {
    return "AuditLogEntry{" + "id=" + id + ", createTime=" + createTime + ", commandType="
        + commandType + ", outputDirectories=" + outputDirectories + ", referenceTables="
        + referenceTables + ", outputTables=" + outputTables + ", outputPartitions="
        + outputPartitions + ", renameFromTable=" + renameFromTable + ", renameFromPartition="
        + renameFromPartition + '}';
  }

  public List<String> getOutputDirectories() {
    return outputDirectories;
  }

  public List<Table> getOutputTables() {
    return outputTables;
  }

  public List<NamedPartition> getOutputPartitions() {
    return outputPartitions;
  }

  public List<Table> getReferenceTables() {
    return referenceTables;
  }

  public Table getRenameFromTable() {
    return renameFromTable;
  }

  public NamedPartition getRenameFromPartition() {
    return renameFromPartition;
  }

  public String getCommand() {
    return command;
  }
}
