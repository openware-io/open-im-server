package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.StoreTimeService;
import io.openware.platform.tenant.domain.store.model.Store;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门店配置写路径（多时区批 1：门店时区 + 营业日切点）。
 *
 * <p>依据 `docs/renovation/MULTI_TIMEZONE_DESIGN.md` §2.1（时区归属层级、门店级为权威来源、写路径 fail-closed）
 * 与 §3.1（切点默认 `04:00`、允许范围 `00:00`–`12:00`）。
 *
 * <p>边界与失败口径：
 * <ul>
 *   <li><b>租户边界</b>：门店必须属于签名上下文的租户，否则 403 {@code TENANT_SCOPE_DENIED}；
 *       用 {@link StoreRepository#findOwnerTenantId(Long)}（忽略租户拦截）区分「不存在」与「跨租户」，
 *       不让越权访问被静默降级成 404；</li>
 *   <li><b>门店边界</b>：签名上下文带门店时，路径门店必须是同一门店，否则 403 {@code STORE_SCOPE_DENIED}；</li>
 *   <li><b>校验</b>：时区与切点一律由 {@link StoreTimeService} 校验（非法 400，且不产生任何写入），
 *       落库前先把值规范化，避免「07:00」「7:00」「07:00:30」三种写法在库里并存；</li>
 *   <li><b>不重算历史</b>：改时区/切点只影响变更之后新产生的记录（文档 §2.1、§3.2），
 *       历史换算不在本类的职责内。</li>
 * </ul>
 */
@Service
public class StoreApplicationService {

    /** 错误码：请求体没有任何可修改字段。 */
    public static final String CODE_STORE_UPDATE_EMPTY = "STORE_UPDATE_EMPTY";

    /** 错误码：门店不存在。 */
    public static final String CODE_STORE_NOT_FOUND = "STORE_NOT_FOUND";

    /** 错误码：签名上下文的门店与目标门店不一致。 */
    public static final String CODE_STORE_SCOPE_DENIED = "STORE_SCOPE_DENIED";

    /** 错误码：门店不属于签名上下文的租户。 */
    public static final String CODE_TENANT_SCOPE_DENIED = "TENANT_SCOPE_DENIED";

    /** 错误码：业态为空、格式非法或不在 ACTIVE 业态字典中。 */
    public static final String CODE_BUSINESS_TYPE_INVALID = "BUSINESS_TYPE_INVALID";

    /** 写权限码：日界决定日结/报表归日，属经营配置级（见原控制器常量注释）。 */
    private static final String PERMISSION_TENANT_MANAGE = "tenant.tenant.manage";
    /** 审计动作码（sdk/infrastructure AuditActions.java 已登记）。 */
    private static final String ACTION_STORE_UPDATE = "tenant.store.update";
    /** 审计资源类型。 */
    private static final String RESOURCE_TYPE_STORE = "tnt_store";

    private final StoreRepository storeRepository;
    private final AuditClient auditClient;
    private final PermissionSnapshotProvider permissionSnapshotProvider;

    /** 兼容既有单测/装配：不传审计与快照依赖时按关闭态处理。 */
    public StoreApplicationService(StoreRepository storeRepository) {
        this(storeRepository, AuditClient.disabled(), null);
    }

    @Autowired
    public StoreApplicationService(StoreRepository storeRepository, AuditClient auditClient,
                                   PermissionSnapshotProvider permissionSnapshotProvider) {
        this.storeRepository = storeRepository;
        this.auditClient = auditClient;
        this.permissionSnapshotProvider = permissionSnapshotProvider;
    }

    /** 当前租户门店列表（可选状态过滤）。 */
    @Transactional(readOnly = true)
    public List<Store> list(String status) {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        return storeRepository.listByTenant(tenantId, null, null, status);
    }

    /**
     * 门店配置修改入口：签名上下文、权限、校验、落库、审计与快照失效都在应用服务完成。
     *
     * <p>时区/切点决定「哪天算一天」，属于对账口径变更：成功与失败都必须留痕。
     */
    @Transactional
    public StoreUpdateResult updateWithAudit(Long storeId, String timezone, String businessDayCutoff,
                                             String businessType) {
        try {
            // 先判「有没有身份」（401），再判「有没有权限」（403），避免无身份被误报成越权。
            TenantContext context = requireContext();
            PermissionGuard.require(PERMISSION_TENANT_MANAGE);
            StoreUpdateResult result = update(context.tenantId(), context.storeId(), storeId,
                    context.accountId(), timezone, businessDayCutoff, businessType);

            // 时区/切点决定「哪天算一天」，属于对账口径变更：必须留痕变更前/后值。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(context.tenantId())
                    .storeId(storeId)
                    .operatorId(context.accountId())
                    .action(ACTION_STORE_UPDATE)
                    .resourceType(RESOURCE_TYPE_STORE)
                    .resourceId(String.valueOf(storeId))
                    .resourceName(result.store().name())
                    .detailJson(detailJson(result))
                    .build());
            if (permissionSnapshotProvider != null && businessType != null) {
                permissionSnapshotProvider.evictAll();
            }
            return result;
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/越权门店/跨租户/时区非法/切点非法/落库失败）：审计只 WARN，异常原样抛出。
            recordFailure(storeId, timezone, businessDayCutoff, businessType, failure);
            throw failure;
        }
    }

    /** 审计详情：只记受控值（IANA id 与 HH:mm:ss），不含门店自由文本。 */
    private static String detailJson(StoreUpdateResult result) {
        return "{\"timezone\":{\"before\":" + jsonText(result.timezoneBefore())
                + ",\"after\":" + jsonText(result.timezoneAfter()) + "}"
                + ",\"businessDayCutoff\":{\"before\":" + jsonText(result.cutoffBefore())
                + ",\"after\":" + jsonText(result.cutoffAfter()) + "}"
                + ",\"businessType\":{\"before\":" + jsonText(result.businessTypeBefore())
                + ",\"after\":" + jsonText(result.businessTypeAfter()) + "}}";
    }

    /**
     * 领域内失败留痕：改门店时区/切点失败必须可回溯。请求值来自 HTTP，含引号/反斜杠时会破坏 JSON，
     * 所以在这里转义；空值写成 JSON null。
     */
    private void recordFailure(Long storeId, String timezone, String businessDayCutoff, String businessType,
                               RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        Long tenantId = context == null ? null : context.tenantId();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .storeId(storeId)
                .operatorId(context == null ? null : context.accountId())
                .action(ACTION_STORE_UPDATE)
                .resourceType(RESOURCE_TYPE_STORE)
                .resourceId(storeId == null ? null : String.valueOf(storeId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"requestedTimezone\":" + jsonText(timezone)
                        + ",\"requestedBusinessDayCutoff\":" + jsonText(businessDayCutoff)
                        + ",\"requestedBusinessType\":" + jsonText(businessType) + "}")
                .build());
    }

    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 租户上下文：写接口一律取签名上下文，绝不从请求参数选租户。 */
    private static TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context;
    }

    /**
     * 修改门店时区、营业日切点和业态；所有字段在同一事务内校验并更新。
     * 业态只能来自 ACTIVE 的 {@code tnt_business_type}，请求值不会成为事实来源。
     */
    @Transactional
    public StoreUpdateResult update(long tenantId, Long contextStoreId, Long storeId, Long operatorId,
                                    String timezone, String businessDayCutoff, String businessType) {
        if (timezone == null && businessDayCutoff == null && businessType == null) {
            throw new ApiException(400, CODE_STORE_UPDATE_EMPTY,
                    "没有可修改的字段：请至少传 timezone、businessDayCutoff 或 businessType");
        }

        ZoneId zone = timezone == null ? null : StoreTimeService.requireZoneId(timezone);
        LocalTime cutoff = businessDayCutoff == null
                ? null : StoreTimeService.requireBusinessDayCutoff(businessDayCutoff);
        String normalizedBusinessType = businessType == null ? null : requireBusinessType(businessType);

        Store store = requireScopedStore(tenantId, contextStoreId, storeId);
        String timezoneBefore = store.timezone();
        String cutoffBefore = store.businessDayCutoff();
        String businessTypeBefore = store.businessType();
        String timezoneAfter = zone == null ? timezoneBefore : zone.getId();
        String cutoffAfter = cutoff == null ? cutoffBefore : StoreTimeService.formatBusinessDayCutoff(cutoff) + ":00";
        String businessTypeAfter = normalizedBusinessType == null ? businessTypeBefore : normalizedBusinessType;

        // 只写本次提交的列：门店字段很多，整体保存会覆盖并发编辑改动的其它列。
        storeRepository.updateSchedule(storeId,
                zone != null ? timezoneAfter : null,
                cutoff != null ? cutoffAfter : null,
                normalizedBusinessType != null ? businessTypeAfter : null,
                operatorId);

        Store updated = withSchedule(store, timezoneAfter, cutoffAfter, businessTypeAfter);
        return new StoreUpdateResult(updated, timezoneBefore, timezoneAfter, cutoffBefore, cutoffAfter,
                businessTypeBefore, businessTypeAfter);
    }

    /**
     * 修改门店时区与营业日切点：字段级可选，未传的字段保持原值。
     *
     * @param tenantId           已由签名上下文收敛的租户
     * @param contextStoreId     签名上下文里的门店（可为 {@code null} = 租户/组织级上下文）
     * @param storeId            路径上的门店主键
     * @param operatorId         操作人（写入 {@code updated_by} 并进审计）
     * @param timezone           IANA 时区 id，{@code null} 表示不修改
     * @param businessDayCutoff  `HH:mm` / `HH:mm:ss`，{@code null} 表示不修改
     * @return 变更后的门店与前后值（供审计 detail 与响应）
     */
    @Transactional
    public StoreScheduleResult updateSchedule(long tenantId, Long contextStoreId, Long storeId, Long operatorId,
                                              String timezone, String businessDayCutoff) {
        StoreUpdateResult result = update(tenantId, contextStoreId, storeId, operatorId,
                timezone, businessDayCutoff, null);
        return new StoreScheduleResult(result.store(), result.timezoneBefore(), result.timezoneAfter(),
                result.cutoffBefore(), result.cutoffAfter());
    }

    private String requireBusinessType(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 32) {
            throw new ApiException(400, CODE_BUSINESS_TYPE_INVALID, "businessType 非法");
        }
        String activeCode = storeRepository.findActiveBusinessTypeCode(normalized);
        if (activeCode == null) {
            throw new ApiException(400, CODE_BUSINESS_TYPE_INVALID, "businessType 不是 ACTIVE 业态");
        }
        return activeCode.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 修改当前门店 Logo（门店级品牌展示，不涉及经营报表口径）。
     *
     * <p>图片本身由媒体服务上传，这里只保存 URL；空值表示恢复默认图。写入前校验 URL 形态与长度，
     * 避免把 javascript:、协议相对 URL 或超长文本写进 tnt_store.logo_url。
     */
    @Transactional
    public Store updateLogoWithAudit(Long storeId, String logoUrl) {
        TenantContext context = requireContext();
        PermissionGuard.require("tenant.store.manage");
        Store store = requireScopedStore(context.tenantId(), context.storeId(), storeId);
        String normalized = normalizeLogoUrl(logoUrl);
        if (!storeRepository.updateLogo(storeId, normalized, context.accountId())) {
            throw new ApiException(409, "STORE_LOGO_UPDATE_FAILED", "门店 Logo 更新失败");
        }
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context.tenantId())
                .storeId(storeId)
                .action("tenant.store.logo.update")
                .resourceType("tnt_store")
                .resourceId(String.valueOf(storeId))
                .resourceName(store.name())
                .detailJson("{\"logoUrl\":\"" + jsonText(normalized) + "\"}")
                .build());
        return withLogo(store, normalized);
    }

    private static String normalizeLogoUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 512) {
            throw new ApiException(400, "STORE_LOGO_INVALID", "门店 Logo URL 不能超过 512 个字符");
        }
        if (normalized.startsWith("//") || normalized.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
            throw new ApiException(400, "STORE_LOGO_INVALID", "门店 Logo URL 非法");
        }
        if (!normalized.startsWith("/") && !normalized.startsWith("https://")) {
            throw new ApiException(400, "STORE_LOGO_INVALID", "门店 Logo 必须是上传后的相对地址或 HTTPS 地址");
        }
        return normalized;
    }

    private static Store withLogo(Store store, String logoUrl) {
        return new Store(store.id(), store.tenantId(), store.organizationId(), store.code(), store.name(),
                store.businessType(), store.countryCode(), store.regionCode(), store.timezone(),
                store.defaultCurrency(), store.locale(), store.taxProfileId(), store.businessDayCutoff(),
                store.status(), logoUrl);
    }

    /**
     * 按签名上下文加载门店：跨门店 → 403，跨租户 → 403，不存在 → 404。
     *
     * <p>先判门店边界再查库：门店上下文是最便宜的一次比较，越权请求不必触达数据库。
     */
    public Store requireScopedStore(long tenantId, Long contextStoreId, Long storeId) {
        if (contextStoreId != null && contextStoreId > 0 && !contextStoreId.equals(storeId)) {
            throw new ApiException(403, CODE_STORE_SCOPE_DENIED, "只能修改当前上下文门店的门店配置");
        }
        Long ownerTenantId = storeRepository.findOwnerTenantId(storeId)
                .orElseThrow(() -> new ApiException(404, CODE_STORE_NOT_FOUND, "门店不存在: " + storeId));
        if (ownerTenantId != tenantId) {
            throw new ApiException(403, CODE_TENANT_SCOPE_DENIED, "只能修改当前上下文租户的门店配置");
        }
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ApiException(404, CODE_STORE_NOT_FOUND, "门店不存在: " + storeId));
    }

    /** 变更后的门店快照：不可变聚合按需生成新实例（审计字段由适配器负责）。 */
    private static Store withSchedule(Store store, String timezone, String cutoff, String businessType) {
        return new Store(store.id(), store.tenantId(), store.organizationId(), store.code(), store.name(),
                businessType, store.countryCode(), store.regionCode(), timezone, store.defaultCurrency(),
                store.locale(), store.taxProfileId(), cutoff, store.status(), store.logoUrl());
    }

    /**
     * 门店时区/切点变更结果。     *
     * @param store          变更后的门店（已回填新值，直接作为响应体）
     * @param timezoneBefore 变更前门店时区
     * @param timezoneAfter  变更后门店时区
     * @param cutoffBefore   变更前营业日切点（DB 的 `HH:mm:ss` 文本）
     * @param cutoffAfter    变更后营业日切点（DB 的 `HH:mm:ss` 文本）
     */
    public record StoreScheduleResult(Store store, String timezoneBefore, String timezoneAfter,
                                      String cutoffBefore, String cutoffAfter) {
    }

    public record StoreUpdateResult(Store store, String timezoneBefore, String timezoneAfter,
                                    String cutoffBefore, String cutoffAfter,
                                    String businessTypeBefore, String businessTypeAfter) {
    }
}
