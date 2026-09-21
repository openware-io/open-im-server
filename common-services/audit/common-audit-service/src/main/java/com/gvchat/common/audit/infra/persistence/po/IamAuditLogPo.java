package com.gvchat.common.audit.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 统一审计日志表 {@code iam_audit_log} 的持久化对象（字段与迁移脚本一一对应）。
 *
 * <p>主键策略为 {@link IdType#INPUT}：ID 由应用侧生成（{@code AuditIdGenerator}）。
 * 不能再用 {@code AUTO_INCREMENT}——批量上报走多行 {@code INSERT}，插入后无法逐条回读主键，
 * 而台账在抢占幂等键时就要写下记录 ID，因此主键必须在插入前确定。
 * 若这里仍是 AUTO，MyBatis-Plus 会把 id 列排除在 INSERT 之外，导致库里的自增值与台账记录不一致。
 */
@Getter
@Setter
@TableName("iam_audit_log")
public class IamAuditLogPo {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long organizationId;
    private Long storeId;
    private Long operatorId;
    private String operatorName;
    private String operatorAccount;
    private String operatorType;
    private String action;
    private String actionLabel;
    private String resourceType;
    private String resourceId;
    private String resourceName;
    private String result;
    private String errorCode;
    private String ip;
    private String userAgent;
    private String requestId;
    private String traceId;
    private String sourceService;
    private String idempotencyKey;
    private String detailJson;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
