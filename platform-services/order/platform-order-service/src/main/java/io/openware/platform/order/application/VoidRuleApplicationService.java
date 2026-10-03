package io.openware.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.infra.persistence.mapper.OrdVoidRuleConfigMapper;
import io.openware.platform.order.infra.persistence.po.OrdVoidRuleConfigPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/** Order 域作废审批规则，解析顺序为门店覆盖 > 业态默认 > 租户默认。 */
@Service
public class VoidRuleApplicationService {
    private final OrdVoidRuleConfigMapper mapper;
    private final AuditClient auditClient;

    public VoidRuleApplicationService(OrdVoidRuleConfigMapper mapper, AuditClient auditClient) {
        this.mapper = mapper;
        this.auditClient = auditClient;
    }

    public RuleView resolve(Long tenantId, String businessType, Long storeId) {
        requireTenant(tenantId);
        String business = normalizeBusinessType(businessType);
        OrdVoidRuleConfigPo row = null;
        if (storeId != null && storeId > 0 && business != null) row = find(tenantId, business, storeId);
        if (row == null && business != null) row = find(tenantId, business, 0L);
        if (row == null) row = find(tenantId, "", 0L);
        if (row == null) return new RuleView(false, "DEFAULT", 0, null);
        String source = row.getStoreId() != null && row.getStoreId() > 0 ? "STORE"
                : (row.getBusinessType() == null || row.getBusinessType().isBlank() ? "TENANT" : "BUSINESS");
        return new RuleView(Boolean.TRUE.equals(row.getRequireApproval()), source,
                row.getVersion() == null ? 0 : row.getVersion(), row.getId());
    }

    @Transactional
    public OrdVoidRuleConfigPo save(Long tenantId, SaveCommand command) {
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
        OrdVoidRuleConfigPo idem = findIdempotency(tenantId, command.idempotencyKey());
        if (idem != null) return idem;
        OrdVoidRuleConfigPo row = find(tenantId, business == null ? "" : business, storeId);
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new OrdVoidRuleConfigPo();
            row.setTenantId(tenantId);
            row.setBusinessType(business == null ? "" : business);
            row.setStoreId(storeId);
            row.setRequireApproval(command.requireApproval());
            row.setVersion(0);
            row.setIdempotencyKey(blankToNull(command.idempotencyKey()));
            row.setStatus("ACTIVE");
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException ex) {
                OrdVoidRuleConfigPo existing = findIdempotency(tenantId, command.idempotencyKey());
                if (existing != null) return existing;
                throw ex;
            }
        } else {
            int current = row.getVersion() == null ? 0 : row.getVersion();
            if (command.expectedVersion() != null && command.expectedVersion() != current) {
                throw new ApiException(409, "VOID_RULE_VERSION_CONFLICT", "作废审批规则版本冲突，请刷新后重试");
            }
            int changed = mapper.update(null, new LambdaUpdateWrapper<OrdVoidRuleConfigPo>()
                    .eq(OrdVoidRuleConfigPo::getId, row.getId())
                    .eq(OrdVoidRuleConfigPo::getVersion, current)
                    .set(OrdVoidRuleConfigPo::getRequireApproval, command.requireApproval())
                    .set(OrdVoidRuleConfigPo::getVersion, current + 1)
                    .set(OrdVoidRuleConfigPo::getUpdatedAt, now));
            if (changed == 0) throw new ApiException(409, "VOID_RULE_VERSION_CONFLICT", "作废审批规则已被并发修改，请刷新后重试");
            row.setRequireApproval(command.requireApproval());
            row.setVersion(current + 1);
            row.setUpdatedAt(now);
        }
        audit(tenantId, row);
        return row;
    }

    public boolean isApprovalRequired(Long tenantId, String businessType, Long storeId) {
        return resolve(tenantId, businessType, storeId).requireApproval();
    }

    private OrdVoidRuleConfigPo find(Long tenantId, String businessType, Long storeId) {
        return mapper.selectOne(new LambdaQueryWrapper<OrdVoidRuleConfigPo>()
                .eq(OrdVoidRuleConfigPo::getTenantId, tenantId)
                .eq(OrdVoidRuleConfigPo::getBusinessType, businessType)
                .eq(OrdVoidRuleConfigPo::getStoreId, storeId)
                .eq(OrdVoidRuleConfigPo::getStatus, "ACTIVE")
                .last("LIMIT 1"));
    }

    private OrdVoidRuleConfigPo findIdempotency(Long tenantId, String key) {
        if (key == null || key.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<OrdVoidRuleConfigPo>()
                .eq(OrdVoidRuleConfigPo::getTenantId, tenantId)
                .eq(OrdVoidRuleConfigPo::getIdempotencyKey, key)
                .last("LIMIT 1"));
    }

    private void audit(Long tenantId, OrdVoidRuleConfigPo row) {
        if (auditClient == null) return;
        auditClient.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(row.getStoreId())
                .operatorId(TenantContextHolder.get() == null ? null : TenantContextHolder.get().accountId())
                .action("order.void.rule.save").resourceType("ord_void_rule_config")
                .resourceId(String.valueOf(row.getId())).result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"businessType\":\"" + row.getBusinessType() + "\",\"storeId\":"
                        + row.getStoreId() + ",\"requireApproval\":" + row.getRequireApproval() + "}").build());
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
