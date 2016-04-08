package com.airbnb.di.hive.replication.primitives;

import com.google.common.base.Objects;
import org.apache.hadoop.fs.Path;

import java.util.Optional;

/**
 * Stores information about the estimated task required to replicate a Hive
 * object.
 */
public class TaskEstimate {
    public enum TaskType {
        COPY_UNPARTITIONED_TABLE,
        COPY_PARTITIONED_TABLE,
        COPY_PARTITION,
        DROP_TABLE,
        DROP_PARTITION,
        CHECK_PARTITION,
        NO_OP,
    }

    private TaskType taskType;
    private boolean updateMetadata;
    private boolean updateData;
    private Optional<Path> srcPath;
    private Optional<Path> destPath;

    public TaskEstimate(TaskType taskType,
                        boolean updateMetadata,
                        boolean updateData,
                        Optional<Path> srcPath,
                        Optional<Path> destPath) {
        this.taskType = taskType;
        this.updateMetadata = updateMetadata;
        this.updateData = updateData;
        this.srcPath = srcPath;
        this.destPath = destPath;
    }

    public boolean isUpdateMetadata() {
        return updateMetadata;
    }

    public boolean isUpdateData() {
        return updateData;
    }

    public Optional<Path> getSrcPath() {
        return srcPath;
    }

    public Optional<Path> getDestPath() {
        return destPath;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this).add("taskType", taskType.toString())
                .add("updateMetadata", updateMetadata)
                .add("updateData", updateData)
                .add("srcPath", srcPath)
                .add("destPath", destPath)
                .toString();
    }
}
