package io.openware.common.audit.domain.model;

import java.time.LocalDateTime;

/**
 * 统一审计日志领域实体（事实记录，不可修改、不可物理删除）。
 *
 * <p>字段口径见 {@code docs/renovation/SAAS_PLATFORM_04_DATA.md}：{@code tenant_id = 0} 表示平台级动作
 * （如创建租户），租户视角查询强制按 {@code tenant_id} 收敛，平台视角才允许跨租户。
 *
 * <p><b>为什么不可变且没有 setter</b>（standards/10 §聚合与实体）：本类在注释里自称"事实记录、不可修改"，
 * 而改造前对 25 个字段全开了 {@code @Setter}——任何人都能在落库后改掉任何一列，注释描述的是目标态而非代码。
 * 现在构造走 {@link Builder}，落库前确定身份走 {@link #recorded}，查询期补充展示名走
 * {@link #withOperatorDisplay}：三类写入各自具名、意图明确，非法写入无法表达。
 *
 * <p><b>为什么 {@code createdAt} 留在领域模型</b>（对 standards/10 §聚合装配与持久化契约的**有意偏离**）：
 * 该值有真实读者——{@code AuditLogView.of(...)} 把它输出到查询响应，是已发布的契约字段。
 * 移出领域模型需要为 6 个端口方法引入传输载体并波及查询路径与多个测试类；在这个"审计记录本身必须不可篡改"
 * 的服务里，先消除可写性、把该偏离显式记录，比一次性做载体改造风险更低。
 * 判据与结论见 {@code docs/renovation/STANDARDS_COMPLIANCE_AUDIT_2026-10-16.md} §8.4。
 */
public final class AuditLog {

    /** 操作人类型：平台运营。 */
    public static final String OPERATOR_TYPE_PLATFORM = "PLATFORM";
    /** 操作人类型：租户/门店账号。 */
    public static final String OPERATOR_TYPE_TENANT = "TENANT";
    /** 结果：成功。 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    /** 结果：失败。 */
    public static final String RESULT_FAILURE = "FAILURE";

    private final Long id;
    private final Long tenantId;
    private final Long organizationId;
    private final Long storeId;
    private final Long operatorId;
    private final String operatorName;
    private final String operatorAccount;
    private final String operatorType;
    private final String action;
    private final String actionLabel;
    private final String resourceType;
    private final String resourceId;
    private final String resourceName;
    private final String result;
    private final String errorCode;
    private final String ip;
    private final String userAgent;
    private final String requestId;
    private final String traceId;
    private final String sourceService;
    private final String idempotencyKey;
    private final String detailJson;
    private final LocalDateTime occurredAt;
    private final LocalDateTime createdAt;

    private AuditLog(Builder builder) {
        this.id = builder.id;
        this.tenantId = builder.tenantId;
        this.organizationId = builder.organizationId;
        this.storeId = builder.storeId;
        this.operatorId = builder.operatorId;
        this.operatorName = builder.operatorName;
        this.operatorAccount = builder.operatorAccount;
        this.operatorType = builder.operatorType;
        this.action = builder.action;
        this.actionLabel = builder.actionLabel;
        this.resourceType = builder.resourceType;
        this.resourceId = builder.resourceId;
        this.resourceName = builder.resourceName;
        this.result = builder.result;
        this.errorCode = builder.errorCode;
        this.ip = builder.ip;
        this.userAgent = builder.userAgent;
        this.requestId = builder.requestId;
        this.traceId = builder.traceId;
        this.sourceService = builder.sourceService;
        this.idempotencyKey = builder.idempotencyKey;
        this.detailJson = builder.detailJson;
        this.occurredAt = builder.occurredAt;
        this.createdAt = builder.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 从既有实例派生新实例（供具名迁移方法使用，避免每个方法重写一遍全字段拷贝）。 */
    private Builder toBuilder() {
        return new Builder().id(id).tenantId(tenantId).organizationId(organizationId).storeId(storeId)
                .operatorId(operatorId).operatorName(operatorName).operatorAccount(operatorAccount)
                .operatorType(operatorType).action(action).actionLabel(actionLabel).resourceType(resourceType)
                .resourceId(resourceId).resourceName(resourceName).result(result).errorCode(errorCode).ip(ip)
                .userAgent(userAgent).requestId(requestId).traceId(traceId).sourceService(sourceService)
                .idempotencyKey(idempotencyKey).detailJson(detailJson).occurredAt(occurredAt)
                .createdAt(createdAt);
    }

    /**
     * 落库前确定身份：补齐服务端负责的字段（主键 / 来源服务 / 幂等键 / 时间）。
     *
     * <p><b>主键口径</b>：由应用侧生成。批量上报要压成一条多行 INSERT，插入后无法逐条回读主键，
     * 而接口契约要逐条回执 ID、台账也要在抢占幂等键时写下记录 ID，因此主键必须在插入前确定。
     *
     * <p><b>发生时间口径（回归修复）</b>：上报体提供了 {@code occurredAt} 就以其为准（业务真实发生时间，
     * 允许早于落库时间）；未提供或为空时落 {@code occurredAt = recordedAt}，绝不写 NULL——历史缺陷正是
     * 「缺省写 NULL」导致按发生时间的筛选/排序/时间范围过滤整段漏行。
     *
     * @param newId              应用侧生成的主键
     * @param newIdempotencyKey  幂等键
     * @param recordedAt         落库时刻，同时作为 {@code createdAt}
     * @param fallbackSource     命令未带来源服务时，使用请求头解析出的来源服务
     */
    public AuditLog recorded(Long newId, String newIdempotencyKey, LocalDateTime recordedAt,
                             String fallbackSource) {
        return toBuilder()
                .id(newId)
                .idempotencyKey(newIdempotencyKey)
                .sourceService(sourceService == null ? fallbackSource : sourceService)
                .createdAt(recordedAt)
                .occurredAt(occurredAt == null ? recordedAt : occurredAt)
                .build();
    }

    /**
     * 查询期补充操作人展示名（{@code audit_log} 只存 id，展示名来自 IAM 批量查询）。
     *
     * <p>这是**只读投影的补全**，不是业务变更：值来自另一张表，不参与落库写入。
     */
    public AuditLog withOperatorDisplay(String displayName, String displayAccount) {
        return toBuilder().operatorName(displayName).operatorAccount(displayAccount).build();
    }

    /** 回填主键（仅适配器在插入后使用；领域对象不可变，故返回新实例）。 */
    public AuditLog withId(Long newId) {
        return toBuilder().id(newId).build();
    }

    /**
     * 替换脱敏后的明细 JSON 文本。
     *
     * <p>用于历史数据兼容：早期记录里的 {@code detailJson} 可能是非 JSON 文本或为空，
     * 读取侧按 String 原样输出、由客户端自行解析。
     */
    public AuditLog withDetailJson(String value) {
        return toBuilder().detailJson(value).build();
    }

    public Long getId() { return id; }

    public Long getTenantId() { return tenantId; }

    public Long getOrganizationId() { return organizationId; }

    public Long getStoreId() { return storeId; }

    public Long getOperatorId() { return operatorId; }

    public String getOperatorName() { return operatorName; }

    public String getOperatorAccount() { return operatorAccount; }

    public String getOperatorType() { return operatorType; }

    public String getAction() { return action; }

    public String getActionLabel() { return actionLabel; }

    public String getResourceType() { return resourceType; }

    public String getResourceId() { return resourceId; }

    public String getResourceName() { return resourceName; }

    public String getResult() { return result; }

    public String getErrorCode() { return errorCode; }

    public String getIp() { return ip; }

    public String getUserAgent() { return userAgent; }

    public String getRequestId() { return requestId; }

    public String getTraceId() { return traceId; }

    public String getSourceService() { return sourceService; }

    public String getIdempotencyKey() { return idempotencyKey; }

    public String getDetailJson() { return detailJson; }

    public LocalDateTime getOccurredAt() { return occurredAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    /** 逐字段构造器：25 个字段用位置参数极易错位，具名链式赋值把顺序错误挡在编译期。 */
    public static final class Builder {
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

        public Builder id(Long value) { this.id = value; return this; }

        public Builder tenantId(Long value) { this.tenantId = value; return this; }

        public Builder organizationId(Long value) { this.organizationId = value; return this; }

        public Builder storeId(Long value) { this.storeId = value; return this; }

        public Builder operatorId(Long value) { this.operatorId = value; return this; }

        public Builder operatorName(String value) { this.operatorName = value; return this; }

        public Builder operatorAccount(String value) { this.operatorAccount = value; return this; }

        public Builder operatorType(String value) { this.operatorType = value; return this; }

        public Builder action(String value) { this.action = value; return this; }

        public Builder actionLabel(String value) { this.actionLabel = value; return this; }

        public Builder resourceType(String value) { this.resourceType = value; return this; }

        public Builder resourceId(String value) { this.resourceId = value; return this; }

        public Builder resourceName(String value) { this.resourceName = value; return this; }

        public Builder result(String value) { this.result = value; return this; }

        public Builder errorCode(String value) { this.errorCode = value; return this; }

        public Builder ip(String value) { this.ip = value; return this; }

        public Builder userAgent(String value) { this.userAgent = value; return this; }

        public Builder requestId(String value) { this.requestId = value; return this; }

        public Builder traceId(String value) { this.traceId = value; return this; }

        public Builder sourceService(String value) { this.sourceService = value; return this; }

        public Builder idempotencyKey(String value) { this.idempotencyKey = value; return this; }

        public Builder detailJson(String value) { this.detailJson = value; return this; }

        public Builder occurredAt(LocalDateTime value) { this.occurredAt = value; return this; }

        public Builder createdAt(LocalDateTime value) { this.createdAt = value; return this; }

        public AuditLog build() { return new AuditLog(this); }
    }
}
