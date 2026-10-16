package io.openware.platform.tenant.domain.tenant.model;

import java.time.LocalDateTime;

/**
 * 租户（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy）不在此承载：它们是记账信息，由持久化适配器填充。
 * {@code createdAt} **保留**：它不是记账信息而是业务事实——平台运营按租户创建时间做区间筛选，
 * 因此它是查询与展示口径的一部分。
 *
 * @param id              租户标识
 * @param tenantCode      租户编码（全局唯一，创建幂等的判据）
 * @param name            租户名
 * @param status          状态：PENDING / ACTIVE / SUSPENDED
 * @param defaultLocale   默认语言区域
 * @param defaultTimezone 默认时区
 * @param createdAt       创建时刻（业务事实：区间筛选用）
 */
public record Tenant(
    Long id,
    String tenantCode,
    String name,
    String status,
    String defaultLocale,
    String defaultTimezone,
    LocalDateTime createdAt) {

  /** 已创建、待开通。 */
  public static final String STATUS_PENDING = "PENDING";

  /** 正常可用。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 已停用。 */
  public static final String STATUS_SUSPENDED = "SUSPENDED";

  /**
   * 新建租户：状态固定为 PENDING，尚未落库。
   *
   * <p>状态由工厂固定：新租户必须先待开通再启用，这是开通流程的起点，不该由调用方决定。
   */
  public static Tenant create(String tenantCode, String name, String defaultLocale, String defaultTimezone) {
    return new Tenant(null, tenantCode, name, STATUS_PENDING, defaultLocale, defaultTimezone, null);
  }

  /** 是否已开通可用。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }
}
