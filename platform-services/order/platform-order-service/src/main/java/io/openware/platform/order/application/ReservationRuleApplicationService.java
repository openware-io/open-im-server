package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.ReservationRuleConfigView;
import io.openware.platform.order.domain.reservation.model.ReservationRuleConfig;
import io.openware.platform.order.domain.reservation.repository.ReservationRuleConfigRepository;
import io.openware.platform.order.domain.reservation.repository.ReservationRuleConfigRepository.Persisted;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * 预约规则的 Order 域端口；解析顺序为门店覆盖 > 业态默认 > 租户默认。
 *
 * <p><b>持久化边界</b>：本类只依赖领域端口 {@link ReservationRuleConfigRepository} 与领域模型
 * {@link ReservationRuleConfig}；{@code OrdReservationRuleConfigPo}/{@code OrdReservationRuleConfigMapper}
 * 不再出现在应用层（响应装配走 {@link ReservationRuleConfigView}，字段名与原持久化对象一致）。
 */
@Service
public class ReservationRuleApplicationService {
    private final ReservationRuleConfigRepository repository;
    private final AuditClient auditClient;

    public ReservationRuleApplicationService(ReservationRuleConfigRepository repository, AuditClient auditClient) {
        this.repository = repository;
        this.auditClient = auditClient;
    }

    public RuleView resolve(Long tenantId, String businessType, Long storeId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        String business = normalizeBusinessType(businessType);
        ReservationRuleConfig row = null;
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
        return RuleView.of(row, row.scopeSource());
    }

    /**
     * 保存规则（门店覆盖 / 业态默认 / 租户默认三档之一）。
     *
     * <p>幂等：同租户同 Idempotency-Key 已存在则直接返回既有行（不再校验版本、不再写库）。
     * 覆盖写是**带版本条件的单条 CAS**（{@link ReservationRuleConfigRepository#updateWindowsWithVersion}）：
     * 更新 0 行 → 409 版本冲突，绝不静默覆盖——预约窗口直接决定「客人能不能下单/取消」，
     * 静默覆盖会让门店按 A 的配置解释 B 的请求。
     */
    @Transactional
    public ReservationRuleConfigView save(Long tenantId, SaveCommand command) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        validate(command);
        String business = normalizeBusinessType(command.businessType());
        long storeId = command.storeId() == null ? 0L : command.storeId();
        Optional<Persisted<ReservationRuleConfig>> byKey =
                repository.findByIdempotencyKey(tenantId, command.idempotencyKey());
        if (byKey.isPresent()) {
            return viewOf(byKey.get());
        }
        ReservationRuleConfig row = find(tenantId, business == null ? "" : business, storeId);
        if (row == null) {
            ReservationRuleConfig draft = ReservationRuleConfig.draft(tenantId, business == null ? "" : business,
                    storeId, command.advanceMinutes(), command.cancelMinutes(), command.rescheduleMinutes(),
                    blankToNull(command.idempotencyKey()));
            try {
                Persisted<ReservationRuleConfig> inserted = repository.insert(draft);
                audit(tenantId, inserted.aggregate(), "reservation.rule.save");
                return viewOf(inserted);
            } catch (DuplicateKeyException e) {
                // 并发重试：唯一键（作用域/幂等键）命中后返回既有行。
                Optional<Persisted<ReservationRuleConfig>> existing =
                        repository.findByIdempotencyKey(tenantId, command.idempotencyKey());
                if (existing.isPresent()) {
                    return viewOf(existing.get());
                }
                throw e;
            }
        }
        int current = row.currentVersion();
        if (command.expectedVersion() != null && command.expectedVersion() != current) {
            throw new ApiException(409, "RESERVATION_RULE_VERSION_CONFLICT", "预约规则版本冲突，请刷新后重试");
        }
        ReservationRuleConfig desired = row.updatedWindows(command.advanceMinutes(), command.cancelMinutes(),
                command.rescheduleMinutes());
        Optional<Persisted<ReservationRuleConfig>> updated =
                repository.updateWindowsWithVersion(desired, current);
        if (updated.isEmpty()) {
            throw new ApiException(409, "RESERVATION_RULE_VERSION_CONFLICT", "预约规则已被并发修改，请刷新后重试");
        }
        audit(tenantId, updated.get().aggregate(), "reservation.rule.save");
        return viewOf(updated.get());
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

    /** 解析路径的每一档：只读聚合（记账列不参与判定）。 */
    private ReservationRuleConfig find(Long tenantId, String business, Long storeId) {
        return repository.findActive(tenantId, business, storeId).map(Persisted::aggregate).orElse(null);
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

    private static ReservationRuleConfigView viewOf(Persisted<ReservationRuleConfig> row) {
        return ReservationRuleConfigView.from(row.aggregate(), row.audit());
    }

    private void audit(Long tenantId, ReservationRuleConfig row, String action) {
        if (auditClient == null) return;
        auditClient.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(row.storeId())
                .operatorId(TenantContextHolder.get() == null ? null : TenantContextHolder.get().accountId()).action(action).resourceType("reservation_rule")
                .resourceId(String.valueOf(row.id())).result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"businessType\":\"" + row.businessType() + "\",\"storeId\":" + row.storeId() + "}").build());
    }

    public record SaveCommand(String businessType, Long storeId, Integer advanceMinutes, Integer cancelMinutes,
                              Integer rescheduleMinutes, Integer expectedVersion, String idempotencyKey) {}

    public record RuleView(Integer advanceMinutes, Integer cancelMinutes, Integer rescheduleMinutes, String source) {
        static RuleView defaults() { return new RuleView(0, 0, 0, "DEFAULT"); }
        static RuleView of(ReservationRuleConfig row, String source) {
            return new RuleView(row.advanceMinutes(), row.cancelMinutes(), row.rescheduleMinutes(), source);
        }
    }
}
