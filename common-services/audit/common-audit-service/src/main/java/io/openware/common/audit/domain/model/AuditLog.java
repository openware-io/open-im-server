package io.openware.common.audit.domain.model;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 统一审计日志领域实体（事实记录，不可修改、不可物理删除）。
 *
 * <p>字段口径见 {@code docs/renovation/SAAS_PLATFORM_04_DATA.md}：{@code tenant_id = 0} 表示平台级动作
 * （如创建租户），租户视角查询强制按 {@code tenant_id} 收敛，平台视角才允许跨租户。
 */
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {
    /** 操作人类型：平台运营。 */
    public static final String OPERATOR_TYPE_PLATFORM = "PLATFORM";
    /** 操作人类型：租户/门店账号。 */
    public static final String OPERATOR_TYPE_TENANT = "TENANT";
    /** 结果：成功。 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    /** 结果：失败。 */
    public static final String RESULT_FAILURE = "FAILURE";

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
