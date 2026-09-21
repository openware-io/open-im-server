package com.gvchat.common.audit.domain.retention;

import java.time.LocalDateTime;

/**
 * 审计冷归档清单记录（对应表 {@code iam_audit_archive_manifest}）。
 *
 * <p>它是「哪些月份已归档、是否允许删除分区」的**唯一可追溯证据**：本阶段只把超出热存窗口的月份
 * 标记为 {@link #STATE_ARCHIVED} 并移出可查范围，**不投递对象存储、不 DROP PARTITION**；
 * 将来落地投递与删除时，硬约束是「清单存在 + 校验和复核通过」才允许删分区。
 */
public record AuditArchiveManifest(
        Long id,
        String period,
        String partitionName,
        String objectPath,
        long rowCount,
        String checksumSha256,
        String state,
        Long operatorId,
        LocalDateTime createdAt,
        LocalDateTime droppedAt) {

    /** 已归档：超出热存窗口、移出可查范围（数据仍在库里，本阶段不删分区）。 */
    public static final String STATE_ARCHIVED = "ARCHIVED";

    /** 法律保留：命中保留策略的月份，不归档、不删除、仍可查。 */
    public static final String STATE_HELD = "HELD";

    /** 分区已删除（投递与删除落地后才会出现）。 */
    public static final String STATE_DROPPED = "DROPPED";
}
