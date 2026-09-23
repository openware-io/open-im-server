package io.openware.common.audit.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 冷归档清单表 {@code iam_audit_archive_manifest} 的持久化对象（对应 migration-audit/V3）。 */
@Getter
@Setter
@TableName("iam_audit_archive_manifest")
public class AuditArchiveManifestPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归档月份 YYYY-MM。 */
    private String period;
    /** 被归档的分区名（如 p202508）。 */
    private String partitionName;
    /** 归档对象路径（本阶段未投递时为空）。 */
    private String objectPath;
    /** 归档行数。 */
    private Long rowCount;
    /** 归档文件校验和（删除前必须复核）。 */
    private String checksumSha256;
    /** ARCHIVED / HELD / DROPPED。 */
    private String state;
    private Long operatorId;
    private LocalDateTime createdAt;
    private LocalDateTime droppedAt;
}
