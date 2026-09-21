package com.gvchat.platform.admin.infra.security;

import java.time.Duration;
import java.util.Set;

/**
 * SaaS 后台登录会话有效期：默认 2 小时（安全基线），登录用户可选 2 小时 / 1 天 / 7 天 / 30 天。
 * 仅允许白名单时长，非法值回退默认，避免客户端任意指定超长有效期。
 *
 * <p><b>与集团门户的继承关系</b>：从集团统一登录门户经一次性 sso_ticket 进入本后台时，会话有效期
 * **继承集团会话的剩余时长**，本后台只用 {@link #MAX} 封顶（不能用更短的值截断，否则门户选的
 * 7 天 / 30 天在本后台会缩水）。{@link #SSO} 只在票据拿不到到期时间时兜底。
 */
public final class SessionTtl {

  public static final Duration DEFAULT = Duration.ofHours(2);
  /** SSO 兜底时长：票据未携带到期时间时使用（正常路径都继承集团会话剩余时长）。 */
  public static final Duration SSO = Duration.ofDays(1);
  /** 本后台允许的最长会话（30 天，与集团门户白名单上限一致）：SSO 继承时的封顶值。 */
  public static final Duration MAX = Duration.ofHours(720);
  private static final Set<Long> ALLOWED_HOURS = Set.of(2L, 24L, 168L, 720L);

  private SessionTtl() {}

  public static Duration resolve(Integer ttlHours) {
    if (ttlHours == null || !ALLOWED_HOURS.contains(Long.valueOf(ttlHours))) {
      return DEFAULT;
    }
    return Duration.ofHours(ttlHours);
  }
}
