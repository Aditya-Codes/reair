package com.airbnb.hive;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.Lists;

import com.airbnb.di.db.DbConnectionFactory;
import com.airbnb.di.db.StaticDbConnectionFactory;
import com.airbnb.di.hive.hooks.AuditLogHook;
import com.airbnb.di.hive.hooks.AuditLogHookUtils;
import com.airbnb.di.hive.hooks.HiveOperation;
import com.airbnb.di.utils.EmbeddedMySqlDb;
import com.airbnb.di.utils.ReplicationTestUtils;
import com.airbnb.di.utils.TestDbCredentials;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.MapRedStats;
import org.apache.hadoop.mapred.Counters;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AuditLogHookTest {

  private static final Log LOG = LogFactory.getLog(
      AuditLogHookTest.class);

  protected static EmbeddedMySqlDb embeddedMySqlDb;

  protected static final String DB_NAME = "audit_log_db";
  protected static final String AUDIT_LOG_TABLE_NAME = "audit_log";
  protected static final String OUTPUT_OBJECTS_TABLE_NAME = "audit_objects";
  protected static final String MAP_RED_STATS_TABLE_NAME = "map_red_stats";

  protected static final String DEFAULT_QUERY_STRING = "Example query string";

  @BeforeClass
  public static void setupClass() {
    embeddedMySqlDb = new EmbeddedMySqlDb();
    embeddedMySqlDb.startDb();
  }

  /**
   * Generates a database connection factory for use in testing.
   *
   * @return the database connection factory
   *
   * @throws IOException TODO
   * @throws SQLException TODO
   */
  public static DbConnectionFactory getDbConnectionFactory()
      throws IOException, SQLException {
    TestDbCredentials testDbCredentials = new TestDbCredentials();
    DbConnectionFactory dbConnectionFactory = new StaticDbConnectionFactory(
        ReplicationTestUtils.getJdbcUrl(embeddedMySqlDb),
        testDbCredentials.getReadWriteUsername(),
        testDbCredentials.getReadWritePassword());
    return dbConnectionFactory;
  }

  /**
   * Resets the testing database.
   *
   * @throws IOException TODO
   * @throws SQLException TODO
   */
  public static void resetState() throws IOException, SQLException {
    DbConnectionFactory dbConnectionFactory = getDbConnectionFactory();
    ReplicationTestUtils.dropDatabase(dbConnectionFactory, DB_NAME);
    AuditLogHookUtils.setupAuditLogTables(
        dbConnectionFactory,
        DB_NAME,
        AUDIT_LOG_TABLE_NAME,
        OUTPUT_OBJECTS_TABLE_NAME,
        MAP_RED_STATS_TABLE_NAME);
  }

  @Test
  public void testAuditLogTable() throws Exception {
    // Setup the audit log DB
    resetState();

    TestDbCredentials testDbCredentials = new TestDbCredentials();
    final DbConnectionFactory dbConnectionFactory = new StaticDbConnectionFactory(
        ReplicationTestUtils.getJdbcUrl(embeddedMySqlDb),
        testDbCredentials.getReadWriteUsername(),
        testDbCredentials.getReadWritePassword());

    final AuditLogHook auditLogHook = new AuditLogHook(testDbCredentials);

    // Set up the source
    org.apache.hadoop.hive.ql.metadata.Table inputTable =
        new org.apache.hadoop.hive.ql.metadata.Table(
            "test_db",
            "test_source_table");
    List<org.apache.hadoop.hive.ql.metadata.Table> inputTables =
        new ArrayList<>();
    inputTables.add(inputTable);

    org.apache.hadoop.hive.ql.metadata.Table outputTable =
        new org.apache.hadoop.hive.ql.metadata.Table(
            "test_db",
            "test_output_table");
    outputTable.setCreateTime(0);

    List<org.apache.hadoop.hive.ql.metadata.Table> outputTables =
        new ArrayList<>();
    outputTables.add(outputTable);

    Map<String, MapRedStats> mapRedStatsPerStage = new HashMap<>();
    MapRedStats stageOneStats = new MapRedStats(2, 3, 2500, true, "fakeJobId");
    Counters counters = new Counters();
    counters.incrCounter("SomeCounterGroupName", "SomeCounterName", 3);
    stageOneStats.setCounters(counters);
    mapRedStatsPerStage.put("Stage-1", stageOneStats);

    HiveConf hiveConf = AuditLogHookUtils.getHiveConf(
        embeddedMySqlDb,
        DB_NAME,
        AUDIT_LOG_TABLE_NAME,
        OUTPUT_OBJECTS_TABLE_NAME,
        MAP_RED_STATS_TABLE_NAME);
    AuditLogHookUtils.insertAuditLogEntry(
        auditLogHook,
        HiveOperation.QUERY,
        DEFAULT_QUERY_STRING,
        inputTables,
        new ArrayList<>(),
        outputTables,
        new ArrayList<>(),
        mapRedStatsPerStage,
        hiveConf);

    // Check the query audit log
    List<String> auditCoreLogColumnsToCheck = Lists.newArrayList(
        "command_type", "command", "inputs", "outputs");

    List<String> auditCoreLogRow = ReplicationTestUtils.getRow(
        dbConnectionFactory,
        DB_NAME,
        AUDIT_LOG_TABLE_NAME,
        auditCoreLogColumnsToCheck,
        null);

    List<String> expectedDbRow = Lists.newArrayList(
        "QUERY",
        DEFAULT_QUERY_STRING,
        "{\"tables\":[\"test_db.test_source_table\"]}",
        "{\"tables\":[\"test_db.test_output_table\"]}");
    assertEquals(expectedDbRow, auditCoreLogRow);

    // Check the output objects audit log
    List<String> outputObjectsColumnsToCheck = Lists.newArrayList(
        "name", "type", "serialized_object");

    List<String> outputObjectsRow = ReplicationTestUtils.getRow(
        dbConnectionFactory,
        DB_NAME,
        OUTPUT_OBJECTS_TABLE_NAME,
        outputObjectsColumnsToCheck,
        null);

    expectedDbRow = Lists.newArrayList(
        "test_db.test_output_table",
        "TABLE",
        "{\"1\":{\"str\":\"test_"
          + "output_table\"},\"2\":{\"str\":\"test_db\"},\"4\":"
          + "{\"i32\":0},\"5\":{\"i32\":0},\"6\":{\"i3"
          + "2\":0},\"7\":{\"rec\":{\"1\":{\"lst\":[\"rec\",0]}"
          + ",\"3\":{\"str\":\"org.apache.hadoop.mapred.Sequenc"
          + "eFileInputFormat\"},\"4\":{\"str\":\"org.apache.ha"
          + "doop.hive.ql.io.HiveSequenceFileOutputFormat\"},\""
          + "5\":{\"tf\":0},\"6\":{\"i32\":-1},\"7\":{\"rec\":{"
          + "\"2\":{\"str\":\"org.apache.hadoop.hive.serde2.Met"
          + "adataTypedColumnsetSerDe\"},\"3\":{\"map\":[\"str\""
          + ",\"str\",1,{\"serialization.format\":\"1\"}]}}},\""
          + "8\":{\"lst\":[\"str\",0]},\"9\":{\"lst\":[\"rec\","
          + "0]},\"10\":{\"map\":[\"str\",\"str\",0,{}]},\"11\""
          + ":{\"rec\":{\"1\":{\"lst\":[\"str\",0]},\"2\":{\"ls"
          + "t\":[\"lst\",0]},\"3\":{\"map\":[\"lst\",\"str\",0"
          + ",{}]}}}}},\"8\":{\"lst\":[\"rec\",0]},\"9\":{\"map"
          + "\":[\"str\",\"str\",0,{}]},\"12\":{\"str\":\"MANAG"
          + "ED_TABLE\"}}");
    assertEquals(expectedDbRow, outputObjectsRow);

    // Check the map reduce stats audit log
    List<String> mapRedStatsColumnsToCheck = Lists.newArrayList(
        "stage", "mappers", "reducers", "cpu_time", "counters");

    List<String> mapRedStatsRow = ReplicationTestUtils.getRow(
        dbConnectionFactory,
        DB_NAME,
        MAP_RED_STATS_TABLE_NAME,
        mapRedStatsColumnsToCheck,
        null);

    expectedDbRow = Lists.newArrayList(
        "Stage-1",
        "2",
        "3",
        "2500",
        "[{\"groupName\":\"SomeCounterGroupName\",\"counters\":[{\"counterNam"
          + "e\":\"SomeCounterName\",\"value\":3}]}]");
    assertEquals(expectedDbRow, mapRedStatsRow);
  }

  @Test
  public void testAuditLogPartition() throws Exception {
    // Setup the audit log DB
    resetState();

    final TestDbCredentials testDbCredentials = new TestDbCredentials();
    final DbConnectionFactory dbConnectionFactory = new StaticDbConnectionFactory(
        ReplicationTestUtils.getJdbcUrl(embeddedMySqlDb),
        testDbCredentials.getReadWriteUsername(),
        testDbCredentials.getReadWritePassword());

    final AuditLogHook auditLogHook = new AuditLogHook(testDbCredentials);


    // Make a partitioned output table
    org.apache.hadoop.hive.ql.metadata.Table qlTable =
        new org.apache.hadoop.hive.ql.metadata.Table(
            "test_db",
            "test_output_table");
    List<FieldSchema> partitionCols = new ArrayList<>();
    partitionCols.add(new FieldSchema("ds", null, null));
    qlTable.setPartCols(partitionCols);
    qlTable.setDataLocation(new Path("file://a/b/c"));
    qlTable.setCreateTime(0);

    // Make the actual partition
    Map<String, String> partitionKeyValue = new HashMap<>();
    partitionKeyValue.put("ds", "1");
    org.apache.hadoop.hive.ql.metadata.Partition outputPartition =
        new org.apache.hadoop.hive.ql.metadata.Partition(qlTable,
            partitionKeyValue, null);
    outputPartition.setLocation("file://a/b/c");
    List<org.apache.hadoop.hive.ql.metadata.Partition> outputPartitions =
        new ArrayList<>();
    outputPartitions.add(outputPartition);

    HiveConf hiveConf = AuditLogHookUtils.getHiveConf(
        embeddedMySqlDb,
        DB_NAME,
        AUDIT_LOG_TABLE_NAME,
        OUTPUT_OBJECTS_TABLE_NAME,
        MAP_RED_STATS_TABLE_NAME);
    AuditLogHookUtils.insertAuditLogEntry(
        auditLogHook,
        HiveOperation.QUERY,
        DEFAULT_QUERY_STRING,
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        outputPartitions,
        new HashMap<>(),
        hiveConf);

    // Check the query audit log
    List<String> auditCoreLogColumnsToCheck = Lists.newArrayList(
        "command_type", "command", "inputs", "outputs");

    List<String> auditCoreLogRow = ReplicationTestUtils.getRow(
        dbConnectionFactory,
        DB_NAME,
        AUDIT_LOG_TABLE_NAME,
        auditCoreLogColumnsToCheck,
        null);

    List<String> expectedDbRow = Lists.newArrayList(
        "QUERY",
        DEFAULT_QUERY_STRING,
        "{}",
        "{\"partitions\":"
          + "[\"test_db.test_output_table/ds=1\"]}");
    assertEquals(expectedDbRow, auditCoreLogRow);


    // Check the output objects audit log
    List<String> outputObjectsColumnsToCheck = Lists.newArrayList(
        "name", "type", "serialized_object");

    List<String> outputObjectsRow = ReplicationTestUtils.getRow(
        dbConnectionFactory,
        DB_NAME,
        OUTPUT_OBJECTS_TABLE_NAME,
        outputObjectsColumnsToCheck,
        "name = 'test_db.test_output_table/ds=1'");

    expectedDbRow = Lists.newArrayList(
        "test_db.test_output_table/ds=1",
        "PARTITION",
        "{\"1\":{\"lst\":[\"str\",1,\"1\"]},\"2\":{\"str"
          + "\":\"test_db\"},\"3\":{\"str\":\"test_output_table"
          + "\"},\"4\":{\"i32\":0},\"5\":{\"i32\":0},\"6\":{\"rec"
          + "\":{\"1\":{\"lst\":[\"rec\",0]},\"2\":{\"str\":\""
          + "file://a/b/c\"},\"3\":{\"str\":\"org.apache.hadoop."
          + "mapred.SequenceFileInputFormat\"},\"4\":{\"str\":\""
          + "org.apache.hadoop.hive.ql.io.HiveSequenceFileOutput"
          + "Format\"},\"5\":{\"tf\":0},\"6\":{\"i32\":-1},\"7\""
          + ":{\"rec\":{\"2\":{\"str\":\"org.apache.hadoop.hive."
          + "serde2.MetadataTypedColumnsetSerDe\"},\"3\":{\"map\""
          + ":[\"str\",\"str\",1,{\"serialization.format\":\"1\""
          + "}]}}},\"8\":{\"lst\":[\"str\",0]},\"9\":{\"lst\":[\""
          + "rec\",0]},\"10\":{\"map\":[\"str\",\"str\",0,{}]},\""
          + "11\":{\"rec\":{\"1\":{\"lst\":[\"str\",0]},\"2\":{\""
          + "lst\":[\"lst\",0]},\"3\":{\"map\":[\"lst\",\"str\",0"
          + ",{}]}}}}}}");

    assertEquals(expectedDbRow, outputObjectsRow);

    outputObjectsRow = ReplicationTestUtils.getRow(
        dbConnectionFactory,
        DB_NAME,
        OUTPUT_OBJECTS_TABLE_NAME,
        outputObjectsColumnsToCheck,
        "name = 'test_db.test_output_table'");

    expectedDbRow = Lists.newArrayList(
        "test_db.test_output_table",
        "TABLE",
        "{\"1\":{\"str\":\"test_output_table\"},\"2\":{\"str\":\""
          + "test_db\"},\"4\":{\"i32\":0},\"5\":{\"i3"
          + "2\":0},\"6\":{\"i32\":0},\"7\":{\"rec\":{\"1\":{\""
          + "lst\":[\"rec\",0]},\"2\":{\"str\":\"file://a/b/c\""
          + "},\"3\":{\"str\":\"org.apache.hadoop.mapred.Seque"
          + "nceFileInputFormat\"},\"4\":{\"str\":\"org.apache"
          + ".hadoop.hive.ql.io.HiveSequenceFileOutputFormat\""
          + "},\"5\":{\"tf\":0},\"6\":{\"i32\":-1},\"7\":{\"re"
          + "c\":{\"2\":{\"str\":\"org.apache.hadoop.hive.serd"
          + "e2.MetadataTypedColumnsetSerDe\"},\"3\":{\"map\":"
          + "[\"str\",\"str\",1,{\"serialization.format\":\"1\""
          + "}]}}},\"8\":{\"lst\":[\"str\",0]},\"9\":{\"lst\":["
          + "\"rec\",0]},\"10\":{\"map\":[\"str\",\"str\",0,{}]"
          + "},\"11\":{\"rec\":{\"1\":{\"lst\":[\"str\",0]},\"2"
          + "\":{\"lst\":[\"lst\",0]},\"3\":{\"map\":[\"lst\",\""
          + "str\",0,{}]}}}}},\"8\":{\"lst\":[\"rec\",1,{\"1\":"
          + "{\"str\":\"ds\"}}]},\"9\":{\"map\":[\"str\",\"str\""
          + ",0,{}]},\"12\":{\"str\":\"MANAGED_TABLE\"}}");

    assertEquals(expectedDbRow, outputObjectsRow);
  }
}
