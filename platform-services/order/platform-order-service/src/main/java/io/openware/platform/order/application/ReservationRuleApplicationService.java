package io.openware.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.infra.persistence.mapper.OrdReservationRuleConfigMapper;
import io.openware.platform.order.infra.persistence.po.OrdReservationRuleConfigPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/** 预约规则的 Order 域端口；解析顺序为门店覆盖 > 业态默认 > 租户默认。 */
@Service
public class ReservationRuleApplicationService {
    private final OrdReservationRuleConfigMapper mapper;
    private final AuditClient auditClient;

    public ReservationRuleApplicationService(OrdReservationRuleConfigMapper mapper, AuditClient auditClient) {
        this.mapper = mapper;
        this.auditClient = auditClient;
    }

    public RuleView resolve(Long tenantId, String businessType, Long storeId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        String business = normalizeBusinessType(businessType);
        OrdReservationRuleConfigPo row = null;
        if (storeId != null && storeId > 0 && business != null) {
            row = find(tenantId, business, storeId);
        }
        if (row == null && business != null) {
            row = find(tenantId, business, 0L);
        }
        if (row == null) {
            row = find(tenantId, "", 0L);
        }
        if (row == null) {
            return RuleView.defaults();
        }
        return RuleView.of(row, row.getStoreId() != null && row.getStoreId() > 0
                ? "STORE" : (row.getBusinessType() == null || row.getBusinessType().isBlank()
                ? "TENANT" : "BUSINESS"));
    }

    @Transactional
    public OrdReservationRuleConfigPo save(Long tenantId, SaveCommand command) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        validate(command);
        String business = normalizeBusinessType(command.businessType());
        long storeId = command.storeId() == null ? 0L : command.storeId();
        OrdReservationRuleConfigPo byKey = findIdempotency(tenantId, command.idempotencyKey());
        if (byKey != null) return byKey;
        OrdReservationRuleConfigPo row = find(tenantId, business == null ? "" : business, storeId);
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new OrdReservationRuleConfigPo();
            row.setTenantId(tenantId);
            row.setBusinessType(business == null ? "" : business);
            row.setStoreId(storeId);
            row.setAdvanceMinutes(command.advanceMinutes());
            row.setCancelMinutes(command.cancelMinutes());
            row.setRescheduleMinutes(command.rescheduleMinutes());
            row.setVersion(0);
            row.setIdempotencyKey(blankToNull(command.idempotencyKey()));
            row.setStatus("ACTIVE");
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException e) {
                OrdReservationRuleConfigPo existing = findIdempotency(tenantId, command.idempotencyKey());
                if (existing != null) return existing;
                throw e;
            }
        } else {
            int current = row.getVersion() == null ? 0 : row.getVersion();
            if (command.expectedVersion() != null && command.expectedVersion() != current) {
                throw new ApiException(409, "RESERVATION_RULE_VERSION_CONFLICT", "预约规则版本冲突，请刷新后重试");
            }
            LambdaUpdateWrapper<OrdReservationRuleConfigPo> update = new LambdaUpdateWrapper<OrdReservationRuleConfigPo>()
                    .eq(OrdReservationRuleConfigPo::getId, row.getId())
                    .eq(OrdReservationRuleConfigPo::getVersion, current)
                    .set(OrdReservationRuleConfigPo::getAdvanceMinutes, command.advanceMinutes())
                    .set(OrdReservationRuleConfigPo::getCancelMinutes, command.cancelMinutes())
                    .set(OrdReservationRuleConfigPo::getRescheduleMinutes, command.rescheduleMinutes())
                    .set(OrdReservationRuleConfigPo::getVersion, current + 1)
                    .set(OrdReservationRuleConfigPo::getUpdatedAt, now);
            if (mapper.update(null, update) == 0) {
                throw new ApiException(409, "RESERVATION_RULE_VERSION_CONFLICT", "预约规则已被并发修改，请刷新后重试");
            }
            row.setAdvanceMinutes(command.advanceMinutes());
            row.setCancelMinutes(command.cancelMinutes());
            row.setRescheduleMinutes(command.rescheduleMinutes());
            row.setVersion(current + 1);
            row.setUpdatedAt(now);
        }
        audit(tenantId, row, "reservation.rule.save");
        return row;
    }

    public void validateCreate(Long tenantId, String businessType, Long storeId, LocalDateTime startAt) {
        RuleView rule = resolve(tenantId, businessType, storeId);
        requireLeadTime(rule.advanceMinutes(), startAt, "RESERVATION_ADVANCE_WINDOW", "预约必须至少提前");
    }

    public void validateCancel(Long tenantId, String businessType, Long storeId, LocalDateTime startAt) {
        RuleView rule = resolve(tenantId, businessType, storeId);
        requireLeadTime(rule.cancelMinutes(), startAt, "RESERVATION_CANCEL_WINDOW", "预约已进入不可取消窗口，须至少提前");
    }

    public void validateReschedule(Long tenantId, String businessType, Long storeId,
                                   LocalDateTime oldStartAt, LocalDateTime newStartAt) {
        RuleView rule = resolve(tenantId, businessType, storeId);
        requireLeadTime(rule.rescheduleMinutes(), oldStartAt, "RESERVATION_RESCHEDULE_WINDOW", "预约已进入不可改期窗口，须至少提前");
        requireLeadTime(rule.advanceMinutes(), newStartAt, "RESERVATION_ADVANCE_WINDOW", "新预约时间必须至少提前");
    }

    private static void requireLeadTime(Integer minutes, LocalDateTime target, String code, String prefix) {
        if (minutes == null || minutes <= 0 || target == null) return;
        long remaining = Duration.between(LocalDateTime.now(), target).toMinutes();
        if (remaining < minutes) {
            throw new ApiException(422, code, prefix + minutes + "分钟");
        }
    }

    private OrdReservationRuleConfigPo find(Long tenantId, String business, Long storeId) {
        return mapper.selectOne(new LambdaQueryWrapper<OrdReservationRuleConfigPo>()
                .eq(OrdReservationRuleConfigPo::getTenantId, tenantId)
                .eq(OrdReservationRuleConfigPo::getBusinessType, business)
                .eq(OrdReservationRuleConfigPo::getStoreId, storeId)
                .eq(OrdReservationRuleConfigPo::getStatus, "ACTIVE")
                .last("LIMIT 1"));
    }

    private OrdReservationRuleConfigPo findIdempotency(Long tenantId, String key) {
        if (key == null || key.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<OrdReservationRuleConfigPo>()
                .eq(OrdReservationRuleConfigPo::getTenantId, tenantId)
                .eq(OrdReservationRuleConfigPo::getIdempotencyKey, key).last("LIMIT 1"));
    }

    private static void validate(SaveCommand c) {
        if (c == null) throw new ApiException(400, "RESERVATION_RULE_REQUIRED", "缺少预约规则");
        if (c.storeId() != null && c.storeId() < 0) throw new ApiException(400, "STORE_ID_INVALID", "storeId 非法");
        if (c.storeId() != null && c.storeId() > 0 && (c.businessType() == null || c.businessType().isBlank())) {
            throw new ApiException(400, "BUSINESS_TYPE_REQUIRED", "门店覆盖必须指定 businessType");
        }
        if (c.advanceMinutes() == null || c.cancelMinutes() == null || c.rescheduleMinutes() == null
                || c.advanceMinutes() < 0 || c.cancelMinutes() < 0 || c.rescheduleMinutes() < 0) {
            throw new ApiException(400, "RESERVATION_RULE_VALUE_INVALID", "预约规则分钟数必须为非负整数");
        }
    }

    private static String normalizeBusinessType(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,32}")) {
            throw new ApiException(400, "BUSINESS_TYPE_INVALID", "businessType 非法");
        }
        return normalized;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private void audit(Long tenantId, OrdReservationRuleConfigPo row, String action) {
        if (auditClient == null) return;
        auditClient.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(row.getStoreId())
                .operatorId(TenantContextHolder.get() == null ? null : TenantContextHolder.get().accountId()).action(action).resourceType("reservation_rule")
                .resourceId(String.valueOf(row.getId())).result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"businessType\":\"" + row.getBusinessType() + "\",\"storeId\":" + row.getStoreId() + "}").build());
    }

    public record SaveCommand(String businessType, Long storeId, Integer advanceMinutes, Integer cancelMinutes,
                              Integer rescheduleMinutes, Integer expectedVersion, String idempotencyKey) {}

    public record RuleView(Integer advanceMinutes, Integer cancelMinutes, Integer rescheduleMinutes, String source) {
        static RuleView defaults() { return new RuleView(0, 0, 0, "DEFAULT"); }
        static RuleView of(OrdReservationRuleConfigPo row, String source) {
            return new RuleView(row.getAdvanceMinutes(), row.getCancelMinutes(), row.getRescheduleMinutes(), source);
        }
    }
}
