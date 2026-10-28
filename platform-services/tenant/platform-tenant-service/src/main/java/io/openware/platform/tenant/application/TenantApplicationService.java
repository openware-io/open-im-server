package io.openware.platform.tenant.application;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.tenant.domain.tenant.model.Tenant;
import io.openware.platform.tenant.domain.tenant.repository.TenantRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台租户管理（列表 + 创建）。
 *
 * <p>创建是平台级动作：成功/失败都必须留痕，操作人由网关注入的平台作用域签名上下文补全，
 * 审计强制 {@code operator_type=PLATFORM}（{@link AuditClient.AuditRecord#OPERATOR_TYPE_PLATFORM}）。
 * 控制器只做协议转换（standards/10 §应用服务规则）。
 */
@Service
@RequiredArgsConstructor
public class TenantApplicationService {
  private final TenantRepository tenantRepository;
  private final AuditClient auditClient;

  /** 按租户创建时间闭区间筛选；不传时间参数时两端都不设界。 */
  @Transactional(readOnly = true)
  public List<Tenant> list(String from, String to) {
    TimeRange range = TimeRangeParams.parse(from, to);
    return tenantRepository.listCreatedBetween(range.fromInclusive(), range.toInclusive());
  }

  /** 平台启用/停用租户；停用会阻止后续登录上下文选择。 */
  @Transactional
  public Tenant changeStatus(Long id, String status) {
    if (id == null || id <= 0) {
      throw new IllegalArgumentException("tenantId is required");
    }
    String normalized = status == null ? "" : status.trim().toUpperCase();
    if (!List.of(Tenant.STATUS_ACTIVE, Tenant.STATUS_SUSPENDED).contains(normalized)) {
      throw new IllegalArgumentException("status must be ACTIVE or SUSPENDED");
    }
    Tenant current = tenantRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("tenant not found: " + id));
    Tenant saved = tenantRepository.updateStatus(current.withStatus(normalized));
    auditClient.recordAsync(AuditClient.AuditRecord.builder()
        .tenantId(id)
        .action("tenant.status.change")
        .operatorType(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM)
        .resourceType("tnt_tenant").resourceId(String.valueOf(id))
        .resourceName(current.name())
        .detailJson("{\"status\":\"" + normalized + "\"}")
        .build());
    return saved;
  }

  /** 创建租户；tenantCode 已存在则幂等返回既有租户，不重复落库、不重复留痕。 */
  @Transactional
  public Tenant create(String tenantCode, String name, String defaultLocale, String defaultTimezone) {
    try {
      Tenant existing = tenantRepository.findByCode(tenantCode).orElse(null);
      if (existing != null) {
        return existing;
      }
      Tenant tenant = tenantRepository.insert(Tenant.create(tenantCode, name, defaultLocale, defaultTimezone));
      // 平台级动作：tenant_id 记为新租户（便于按租户检索），operator_type 强制 PLATFORM。
      auditClient.recordAsync(AuditClient.AuditRecord.builder()
          .tenantId(tenant.id())
          .action("tenant.create")
          .operatorType(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM)
          .resourceType("tnt_tenant").resourceId(String.valueOf(tenant.id()))
          .resourceName(name)
          .idempotencyKey("tenant-create:" + tenantCode)
          .detailJson("{\"tenantCode\":\"" + tenantCode + "\",\"name\":\"" + name
              + "\",\"status\":\"" + tenant.status() + "\"}")
          .build());
      return tenant;
    } catch (RuntimeException failure) {
      // 失败留痕（编号冲突/参数非法/落库失败）：审计只 WARN，业务异常原样抛出。
      auditClient.recordAsync(AuditClient.AuditRecord.builder()
          .action("tenant.create")
          .operatorType(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM)
          .resourceType("tnt_tenant")
          .resourceId(tenantCode)
          .result(AuditClient.AuditRecord.RESULT_FAILURE)
          .errorCode(AuditErrorCodes.of(failure))
          .detailJson("{\"tenantCode\":\"" + (tenantCode == null ? "" : tenantCode) + "\"}")
          .build());
      throw failure;
    }
  }
}
