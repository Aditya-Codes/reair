package test;

import com.airbnb.di.hive.common.HiveObjectSpec;
import com.airbnb.di.hive.common.HiveMetastoreClient;
import com.airbnb.di.hive.common.HiveMetastoreException;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates a Hive metastore client connected to a Hive metastore Thrift server
 */
public class MockHiveMetastoreClient implements HiveMetastoreClient {

    private Map<String, Database> dbNameToDatabase;
    private Map<HiveObjectSpec, Table> specToTable;
    private Map<HiveObjectSpec, Partition> specToPartition;

    public MockHiveMetastoreClient() {
        dbNameToDatabase = new HashMap<String, Database>();
        specToTable = new HashMap<HiveObjectSpec, Table>();
        specToPartition = new HashMap<HiveObjectSpec, Partition>();
    }

    /**
     *
     * @param t
     * @param p
     * @return the partition name (e.g. ds=1/hr=2) given a Table and Partition
     * object
     * @throws HiveMetastoreException
     */
    private String getPartitionName(Table t, Partition p)
            throws HiveMetastoreException {
        if (t.getPartitionKeys().size() != p.getValues().size()) {
            throw new HiveMetastoreException("Partition column mismatch: " +
                    "table has " + t.getPartitionKeys().size() + " columns " +
                    "while partition has " + p.getValues().size() + " values");
        }

        StringBuilder sb = new StringBuilder();
        // TODO: This doesn't handle escaping of special chars in values
        List<String> keyValues = new ArrayList<String>();
        int i = 0;
        for (FieldSchema field : t.getPartitionKeys()) {
            keyValues.add(field.getName() + "=" + p.getValues().get(i));
            i++;
        }
        return StringUtils.join(keyValues, "/");
    }

    @Override
    public Partition addPartition(Partition p) throws HiveMetastoreException {
        HiveObjectSpec tableSpec = new HiveObjectSpec(p.getDbName(),
                p.getTableName());
        if (!specToTable.containsKey(tableSpec)) {
            throw new HiveMetastoreException("Unknown table: " + tableSpec);
        }
        Table t = specToTable.get(tableSpec);
        String partitionName = getPartitionName(t, p);

        HiveObjectSpec partitionSpec = new HiveObjectSpec(tableSpec.getDbName(),
                tableSpec.getTableName(), partitionName);

        if (specToPartition.containsKey(partitionSpec)) {
            throw new HiveMetastoreException("Partition already exists: " +
                    partitionSpec);
        }

        specToPartition.put(partitionSpec, p);
        return p;
    }

    @Override
    public Table getTable(String dbName, String tableName)
            throws HiveMetastoreException {
        return specToTable.get(new HiveObjectSpec(dbName, tableName));
    }

    @Override
    public Partition getPartition(String dbName,
                                  String tableName,
                                  String partitionName)
            throws HiveMetastoreException {
        return specToPartition.get(new HiveObjectSpec(dbName, tableName,
                partitionName));
    }

    @Override
    public void alterPartition(String dbName,
                               String tableName,
                               Partition p) throws HiveMetastoreException {
        HiveObjectSpec tableSpec = new HiveObjectSpec(p.getDbName(),
                p.getTableName());
        if (!specToTable.containsKey(tableSpec)) {
            throw new HiveMetastoreException("Unknown table: " + tableSpec);
        }
        Table t = specToTable.get(tableSpec);
        String partitionName = getPartitionName(t, p);

        HiveObjectSpec partitionSpec = new HiveObjectSpec(tableSpec.getDbName(),
                tableSpec.getPartitionName(), partitionName);

        if (!specToPartition.containsKey(partitionSpec)) {
            throw new HiveMetastoreException("Partition does not exist: " +
                    partitionSpec);
        }

        specToPartition.put(partitionSpec, p);
    }

    @Override
    public void createDatabase(Database db) throws HiveMetastoreException {
        if (dbNameToDatabase.containsKey(db.getName())) {
            throw new HiveMetastoreException("DB " + db.getName() +
                    " already exists!");
        }
        dbNameToDatabase.put(db.getName(), db);
    }

    @Override
    public Database getDatabase(String dbName) throws HiveMetastoreException {
        return dbNameToDatabase.get(dbName);
    }

    @Override
    public boolean existsDb(String dbName) throws HiveMetastoreException {
        return getDatabase(dbName) != null;
    }

    @Override
    public void createTable(Table t) throws HiveMetastoreException {
        if (!existsDb(t.getDbName())) {
            throw new HiveMetastoreException("DB " + t.getDbName() +
                    " does not exist!");
        }

        HiveObjectSpec tableSpec = new HiveObjectSpec(t.getDbName(),
                t.getTableName());
        if (specToTable.containsKey(tableSpec)) {
            throw new HiveMetastoreException("Table already exists: " +
                    tableSpec);
        }
        specToTable.put(tableSpec, t);
    }

    @Override
    public void alterTable(String dbName, String tableName, Table t)
            throws HiveMetastoreException {
        HiveObjectSpec existingTableSpec = new HiveObjectSpec(dbName,
                tableName);
        HiveObjectSpec newTableSpec = new HiveObjectSpec(t.getDbName(),
                t.getTableName());
        if (!specToTable.containsKey(existingTableSpec)) {
            throw new HiveMetastoreException("Unknown table: " +
                    existingTableSpec);
        }
        Table removedTable = specToTable.remove(existingTableSpec);
        if (removedTable == null) {
            throw new RuntimeException("Shouldn't happen!");
        }
        specToTable.put(newTableSpec, t);
    }

    @Override
    public boolean isPartitioned(String dbName, String tableName)
            throws HiveMetastoreException {
        return getTable(dbName, tableName).getPartitionKeys().size() > 0;
    }

    @Override
    public boolean existsPartition(String dbName, String tableName,
                                   String partitionName) throws HiveMetastoreException {
        return getPartition(dbName, tableName, partitionName) != null;
    }

    @Override
    public boolean existsTable(String dbName, String tableName) throws HiveMetastoreException {
        return getTable(dbName, tableName) != null;
    }



    @Override
    public void dropTable(String dbName, String tableName,
                          boolean deleteData) throws HiveMetastoreException {
        HiveObjectSpec tableSpec = new HiveObjectSpec(dbName, tableName);
        if (!existsTable(dbName, tableName)) {
            throw new HiveMetastoreException("Missing table: " +
                    tableSpec);
        }
        // Remove the table
        specToTable.remove(new HiveObjectSpec(dbName, tableName));

        // Remove associated partitions
        Iterator<Map.Entry<HiveObjectSpec, Partition>> mapIterator =
                specToPartition.entrySet().iterator();

        while (mapIterator.hasNext()) {
            Map.Entry<HiveObjectSpec, Partition> entry = mapIterator.next();
            if (entry.getKey().getTableSpec().equals(tableSpec)) {
                mapIterator.remove();
            }
        }
    }

    @Override
    public void dropPartition(String dbName,
                              String tableName,
                              String partitionName, boolean deleteData)
            throws HiveMetastoreException {
        // TODO: Handle delete data
        HiveObjectSpec partitionSpec = new HiveObjectSpec(dbName, tableName,
                partitionName);
        if (!existsPartition(dbName, tableName, partitionName)) {
            throw new HiveMetastoreException("Missing partition: " +
                    partitionSpec);
        }
        specToPartition.remove(partitionSpec);
    }

    @Override
    public List<String> getPartitionNames(String dbName, String tableName)
            throws HiveMetastoreException {
        List<String> partitionNames = new ArrayList<String>();
        HiveObjectSpec tableSpec = new HiveObjectSpec(dbName, tableName);
        for (Map.Entry<HiveObjectSpec, Partition> entry :
                specToPartition.entrySet()) {
            if (tableSpec.equals(entry.getKey().getTableSpec())) {
                partitionNames.add(entry.getKey().getPartitionName());
            }
        }
        return partitionNames;
    }

    @Override
    public Map<String, String> partitionNameToMap(String partitionName)
            throws HiveMetastoreException {
        LinkedHashMap partitionKeyToValue = new LinkedHashMap<String, String>();
        String[] keyValues = partitionName.split("/");
        for (String keyValue : keyValues) {
            String[] keyValueSplit = keyValue.split("=");
            String key = keyValueSplit[0];
            String value = keyValueSplit[1];
            partitionKeyToValue.put(key, value);
        }

        return partitionKeyToValue;
    }

    @Override
    public List<String> getTables(String dbName, String tableName)
            throws HiveMetastoreException {
        if (!tableName.equals("*")) {
            throw new RuntimeException("Only * (wildcard) is supported in " +
                    "the mock client");
        }
        List<String> tableNames = new ArrayList<String>();

        for (HiveObjectSpec spec : specToTable.keySet()) {
            if (spec.getDbName().equals(dbName)) {
                tableNames.add(spec.getTableName());
            }
        }
        return tableNames;
    }

    private static String partitionSpecToName(Map<String, String> spec) {
        // Note: This does not handle escaping in partition names correctly
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : spec.entrySet()) {
            if (sb.length() != 0) {
                sb.append("/");
            }
            sb.append(entry.getKey() + "=" + entry.getValue());
        }
        return sb.toString();
    }

    public Partition exchangePartition(Map<String, String> partitionSpecs,
                                       String sourceDb,
                                       String sourceTable,
                                       String destDb,
                                       String destinationTableName)
            throws HiveMetastoreException {
        String partitionName = partitionSpecToName(
                partitionSpecs);
        HiveObjectSpec exchangeFromPartitionSpec = new HiveObjectSpec(sourceDb,
                sourceTable, partitionName);
        HiveObjectSpec exchangeToPartitionSpec = new HiveObjectSpec(destDb,
                destinationTableName,
                partitionName);

        if (!existsPartition(sourceDb, sourceTable, partitionName)) {
            throw new HiveMetastoreException(String.format(
                    "Unknown source partition %s.%s/%s",
                    sourceDb,
                    sourceTable,
                    partitionName));
        }

        if (!existsTable(destDb, destinationTableName)) {
            throw new HiveMetastoreException(String.format(
                    "Unknown destination table %s.%s",
                    destDb,
                    destinationTableName));
        }

        Partition p = specToPartition.remove(exchangeFromPartitionSpec);
        p.setDbName(destDb);
        p.setTableName(destinationTableName);
        specToPartition.put(exchangeToPartitionSpec, p);
        return p;
    }

    private String getPartitionName(Table t, List<String> values) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (FieldSchema fs : t.getPartitionKeys()) {
            if (i > 0) {
                sb.append("/");
            }
            sb.append(fs.getName());
            sb.append("=");
            sb.append(values.get(i));
            i++;
        }
        return sb.toString();
    }

    @Override
    public void renamePartition(String db,
                                String table,
                                List<String> partitionValues,
                                Partition p) throws HiveMetastoreException {
        HiveObjectSpec tableSpec = new HiveObjectSpec(db, table);
        Table t = specToTable.get(tableSpec);

        String renameFromPartitionName = getPartitionName(t, partitionValues);
        String renameToPartitionName = getPartitionName(t, p.getValues());

        HiveObjectSpec renameFromSpec = new HiveObjectSpec(
                db, table, renameFromPartitionName);
        HiveObjectSpec renameToSpec = new HiveObjectSpec(
                db, table, renameToPartitionName);

        if (specToPartition.containsKey(renameToSpec)) {
            throw new HiveMetastoreException("Partition already exists: " +
                    renameToSpec);
        }

        if (!specToPartition.containsKey(renameFromSpec)) {
            throw new HiveMetastoreException("Partition doesn't exist: " +
                    renameFromPartitionName);
        }

        Partition removed = specToPartition.remove(renameFromSpec);
        removed.setValues(new ArrayList<String>(p.getValues()));
        specToPartition.put(renameToSpec, removed);
    }
}
