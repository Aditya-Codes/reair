package com.airbnb.di.hive.replication.configuration;

import com.airbnb.di.hive.common.NamedPartition;
import com.airbnb.di.hive.replication.auditlog.AuditLogEntry;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;

/**
 * Created by paul_yang on 8/11/15.
 */
public class PassThoughReplicationFilter implements ReplicationFilter {
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
        return true;
    }

    @Override
    public boolean accept(Table table, NamedPartition partition) {
        return true;
    }
}
