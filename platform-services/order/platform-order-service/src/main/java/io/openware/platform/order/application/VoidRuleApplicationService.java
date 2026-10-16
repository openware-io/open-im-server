package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.order.model.VoidRuleConfig;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.domain.order.repository.VoidRuleConfigRepository;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Order 域作废审批规则，解析顺序为门店覆盖 > 业态默认 > 租户默认。 */
@Service
public class VoidRuleApplicationService {
    private final VoidRuleConfigRepository repository;
    private final AuditClient auditClient;

    public VoidRuleApplicationService(VoidRuleConfigRepository repository, AuditClient auditClient) {
        this.repository = repository;
        this.auditClient = auditClient;
    }

    public RuleView resolve(Long tenantId, String businessType, Long storeId) {
        requireTenant(tenantId);
        String business = normalizeBusinessType(businessType);
        VoidRuleConfig row = null;
        if (storeId != null && storeId > 0 && business != null) row = find(tenantId, business, storeId);
        if (row == null && business != null) row = find(tenantId, business, 0L);
        if (row == null) row = find(tenantId, "", 0L);
        if (row == null) return new RuleView(false, "DEFAULT", 0, null);
        // 档位由列值推导（VoidRuleConfig#scope）：STORE / BUSINESS / TENANT。
        return new RuleView(row.approvalRequired(), row.scope(), row.currentVersion(), row.id());
    }

    /**
     * 保存规则，返回落库后的聚合与记账快照。
     *
     * <p>响应契约（内部端口 {@code PUT /internal/order/void-rules}）带 {@code createdAt}/{@code updatedAt}，
     * 而审计列不进领域模型，因此经 {@link Persisted} 一并交回控制器装配视图。
     */
    @Transactional
    public Persisted<VoidRuleConfig> save(Long tenantId, SaveCommand command) {
        requireTenant(tenantId);
        if (command == null || command.requireApproval() == null) {
            throw new ApiException(400, "VOID_RULE_REQUIRED", "缺少作废审批规则");
        }
        if (command.storeId() != null && command.storeId() < 0) {
            throw new ApiException(400, "STORE_ID_INVALID", "storeId 非法");
        }
        String business = normalizeBusinessType(command.businessType());
        long storeId = command.storeId() == null ? 0L : command.storeId();
        if (storeId > 0 && business == null) {
            throw new ApiException(400, "BUSINESS_TYPE_REQUIRED", "门店覆盖必须指定 businessType");
        }
        Optional<Persisted<VoidRuleConfig>> idem = findIdempotency(tenantId, command.idempotencyKey());
        if (idem.isPresent()) return idem.get();
        Persisted<VoidRuleConfig> existing = findActive(tenantId, business == null ? "" : business, storeId);
        Persisted<VoidRuleConfig> saved;
        if (existing == null) {
            VoidRuleConfig row = new VoidRuleConfig(null, tenantId, business == null ? "" : business, storeId,
                    command.requireApproval(), 0, blankToNull(command.idempotencyKey()),
                    VoidRuleConfig.STATUS_ACTIVE);
            try {
                saved = repository.insert(row);
            } catch (DuplicateKeyException ex) {
                Optional<Persisted<VoidRuleConfig>> winner = findIdempotency(tenantId, command.idempotencyKey());
                if (winner.isPresent()) return winner.get();
                throw ex;
            }
        } else {
            VoidRuleConfig row = existing.aggregate();
            int current = row.currentVersion();
            if (command.expectedVersion() != null && command.expectedVersion() != current) {
                throw new ApiException(409, "VOID_RULE_VERSION_CONFLICT", "作废审批规则版本冲突，请刷新后重试");
            }
            // CAS：只有库中版本仍是读到的那个才写入；受影响 0 行 = 期间已被并发修改。
            saved = repository
                    .updateRequireApprovalWithVersion(row.withRequireApproval(command.requireApproval(), current),
                            current)
                    .orElseThrow(() -> new ApiException(409, "VOID_RULE_VERSION_CONFLICT",
                            "作废审批规则已被并发修改，请刷新后重试"));
        }
        audit(tenantId, saved.aggregate());
        return saved;
    }

    public boolean isApprovalRequired(Long tenantId, String businessType, Long storeId) {
        return resolve(tenantId, businessType, storeId).requireApproval();
    }

    /** 某档规则行（生效中）；解析只关心业务字段，记账快照由保存路径消费。 */
    private VoidRuleConfig find(Long tenantId, String businessType, Long storeId) {
        Persisted<VoidRuleConfig> row = findActive(tenantId, businessType, storeId);
        return row == null ? null : row.aggregate();
    }

    private Persisted<VoidRuleConfig> findActive(Long tenantId, String businessType, Long storeId) {
        return repository.findActive(tenantId, businessType, storeId).orElse(null);
    }

    private Optional<Persisted<VoidRuleConfig>> findIdempotency(Long tenantId, String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return repository.findByIdempotencyKey(tenantId, key);
    }

    private void audit(Long tenantId, VoidRuleConfig row) {
        if (auditClient == null) return;
        auditClient.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(row.storeId())
                .operatorId(TenantContextHolder.get() == null ? null : TenantContextHolder.get().accountId())
                .action("order.void.rule.save").resourceType("ord_void_rule_config")
                .resourceId(String.valueOf(row.id())).result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"businessType\":\"" + row.businessType() + "\",\"storeId\":"
                        + row.storeId() + ",\"requireApproval\":" + row.requireApproval() + "}").build());
    }

    private static void requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) throw new BusinessException("SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
    }

    private static String normalizeBusinessType(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,32}")) throw new ApiException(400, "BUSINESS_TYPE_INVALID", "businessType 非法");
        return normalized;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record SaveCommand(String businessType, Long storeId, Boolean requireApproval,
                              Integer expectedVersion, String idempotencyKey) { }
    public record RuleView(boolean requireApproval, String source, Integer version, Long id) { }
}
