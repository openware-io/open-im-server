package com.gvchat.platform.tenant.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.tenant.PermissionGuard;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.tenant.application.StoreApplicationService;
import com.gvchat.platform.tenant.application.StoreApplicationService.StoreScheduleResult;
import com.gvchat.platform.tenant.infra.persistence.mapper.StoreMapper;
import com.gvchat.platform.tenant.infra.persistence.po.StorePo;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店列表与门店配置修改（ADM，租户后台）。
 * 租户边界由 TenantLineInnerInterceptor 自动附加 tenant_id 过滤（tnt_store 非忽略表）。
 *
 * <p>读写契约：
 * <ul>
 *   <li>{@code GET  /admin/tenant/stores?status=} → {@code StorePo[]}，含 {@code timezone} 与
 *       {@code businessDayCutoff}（多时区展示与编辑的真源，字段名与
 *       `docs/renovation/MULTI_TIMEZONE_DESIGN.md` 一致）；</li>
 *   <li>{@code PUT  /admin/tenant/stores/{id}} body {@code {timezone?, businessDayCutoff?}} → 变更后的 {@code StorePo}。</li>
 * </ul>
 *
 * <p>写接口的边界（文档 §2.1、§3.1）：
 * <ul>
 *   <li>身份 401 → 权限 {@code tenant.tenant.manage} 403 → 校验 400 → 门店/租户边界 403 → 不存在 404；</li>
 *   <li>{@code timezone} 必须是合法 IANA id（拒绝 `+08:00` 这类偏移字面量）；
 *       {@code businessDayCutoff} 必须是 `HH:mm`/`HH:mm:ss` 且落在 `00:00`–`12:00`；</li>
 *   <li>两个字段都可选，但至少传一个；未传的字段保持原值（不做隐式清零）；</li>
 *   <li>写成功与写失败都留审计（动作码 {@code tenant.store.update}，资源 {@code tnt_store}）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/tenant/stores")
public class StoreController {

    /**
     * 写权限码：{@code tenant.tenant.manage}（经营配置级），**不是** {@code tenant.store.manage}。
     *
     * <p>理由：门店时区与营业日切点决定「日界」——它同时约束日结唯一键、报表按营业日聚合与交班时间窗，
     * 属于与币种/租户配置同级的口径类设置；若用 {@code tenant.store.manage}，店长（store.manager 持有该码、
     * 且不持有 {@code tenant.tenant.manage}）就能自行挪动日界，等于可以改变自己门店的日结与报表归日。
     * 该权限也与既有接口契约 `docs/renovation/SAAS_PLATFORM_05_API.md`
     * 中 {@code PUT /api/v1/admin/tenant/stores/{id}} 标注的 {@code tenant.tenant.manage} 一致
     * （两码均已在 IAM 基线登记：{@code V11__seed_operational_iam.sql}）。
     */
    private static final String PERMISSION_TENANT_MANAGE = "tenant.tenant.manage";

    /** 审计动作码（sdk/infrastructure AuditActions.java 已登记：tenant.store.update = 门店修改）。 */
    private static final String ACTION_STORE_UPDATE = "tenant.store.update";

    /** 审计资源类型。 */
    private static final String RESOURCE_TYPE_STORE = "tnt_store";

    private final StoreMapper storeMapper;
    private final StoreApplicationService storeService;
    private final AuditClient auditClient;

    public StoreController(StoreMapper storeMapper, StoreApplicationService storeService, AuditClient auditClient) {
        this.storeMapper = storeMapper;
        this.storeService = storeService;
        this.auditClient = auditClient;
    }

    /** 当前租户门店列表（可选状态过滤）。 */
    @GetMapping
    public List<StorePo> list(@RequestParam(required = false) String status) {
        LambdaQueryWrapper<StorePo> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(StorePo::getStatus, status);
        }
        qw.orderByAsc(StorePo::getId);
        return storeMapper.selectList(qw);
    }

    /** 修改门店时区 / 营业日切点（权限 + 门店/租户边界 + 校验 + 审计，文档 §2.1 S16）。 */
    @PutMapping("/{id}")
    public StorePo update(@PathVariable Long id, @RequestBody(required = false) UpdateStoreRequest request) {
        try {
            // 先判「有没有身份」（401），再判「有没有权限」（403），避免无身份被误报成越权。
            TenantContext context = requireContext();
            PermissionGuard.require(PERMISSION_TENANT_MANAGE);

            StoreScheduleResult result = storeService.updateSchedule(
                    context.tenantId(), context.storeId(), id, context.accountId(),
                    request == null ? null : request.timezone(),
                    request == null ? null : request.businessDayCutoff());

            // 时区/切点决定「哪天算一天」，属于对账口径变更：必须留痕变更前/后值。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(context.tenantId())
                    .storeId(id)
                    .operatorId(context.accountId())
                    .action(ACTION_STORE_UPDATE)
                    .resourceType(RESOURCE_TYPE_STORE)
                    .resourceId(String.valueOf(id))
                    .resourceName(result.store().getName())
                    .detailJson(detailJson(result))
                    .build());

            return result.store();
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/越权门店/跨租户/时区非法/切点非法/落库失败）：审计只 WARN，异常原样抛出。
            recordFailure(id, request, failure);
            throw failure;
        }
    }

    /** 审计详情：只记受控值（IANA id 与 HH:mm:ss），不含门店自由文本。 */
    private static String detailJson(StoreScheduleResult result) {
        return "{\"timezone\":{\"before\":" + jsonText(result.timezoneBefore())
                + ",\"after\":" + jsonText(result.timezoneAfter()) + "}"
                + ",\"businessDayCutoff\":{\"before\":" + jsonText(result.cutoffBefore())
                + ",\"after\":" + jsonText(result.cutoffAfter()) + "}}";
    }

    /**
     * 领域内失败留痕：改门店时区/切点失败必须可回溯（否则「为什么改不了」只能翻应用日志）。
     * 请求值来自 HTTP，含引号/反斜杠时会破坏 JSON，所以在这里转义；空值写成 JSON null。
     */
    private void recordFailure(Long storeId, UpdateStoreRequest request, RuntimeException failure) {
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
                .detailJson("{\"requestedTimezone\":" + jsonText(request == null ? null : request.timezone())
                        + ",\"requestedBusinessDayCutoff\":"
                        + jsonText(request == null ? null : request.businessDayCutoff()) + "}")
                .build());
    }

    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 租户上下文：写接口一律取签名上下文，绝不从请求参数选租户（与 TenantCurrencyController 同款边界）。 */
    private static TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context;
    }

    /**
     * 门店配置修改请求：两个字段都可选，至少传一个（都不传 → 400 {@code STORE_UPDATE_EMPTY}）。
     *
     * @param timezone           IANA 时区 id，例如 {@code Asia/Bangkok}
     * @param businessDayCutoff  营业日切点，{@code HH:mm} 或 {@code HH:mm:ss}，取值 {@code 00:00}–{@code 12:00}
     */
    public record UpdateStoreRequest(String timezone, String businessDayCutoff) {
    }
}
