package com.airbnb.di.hive.replication.deploy;

import com.airbnb.di.hive.common.NamedPartition;
import com.airbnb.di.hive.replication.auditlog.AuditLogEntry;
import com.airbnb.di.hive.replication.filter.ReplicationFilter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.Table;

public class GoldToSilverReplicationFilter implements ReplicationFilter {
  private static final Log LOG = LogFactory.getLog(GoldToSilverReplicationFilter.class);

  @Override
  public void setConf(Configuration conf) {
    return;
  }

  @Override
  public boolean accept(AuditLogEntry entry) {
    return true;
  }

  @Override
  public boolean accept(Table table) {
    if (table.getDbName().startsWith("tmp")) {
      return false;
    }

    if (table.getTableName().startsWith("staging")) {
      return false;
    }

    if (table.getTableName().startsWith("tmp")) {
      return false;
    }

    return true;
  }

  @Override
  public boolean accept(Table table, NamedPartition partition) {
    return accept(table);
  }
}
