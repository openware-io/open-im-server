package com.gvchat.group.idaas.infra.security;

import java.time.Duration;
import java.util.Set;

/**
 * 集团门户会话有效期白名单。
 *
 * <p>门户「登录有效时长」下拉只能选这里列出的四档；非法/缺省值回退 {@link #DEFAULT}（24 小时）。
 * <b>上限即 {@link #MAX}</b>：从集团入口跳转到各后台时（一次性 sso_ticket）继承的就是这个有效期，
 * 下游后台必须用「自己的上限」与「集团会话剩余时长」取较小值，不得再压到更短（SaaS 后台曾写死
 * 1 天，把门户选的 7 天 / 30 天截断了）。
 */
public final class GroupSessionTtl {
  /** 默认：门户未选择时 24 小时。 */
  public static final Duration DEFAULT = Duration.ofHours(24);
  /** 上限：门户可选的最长有效期（30 天）＝下游后台继承时的封顶值。 */
  public static final Duration MAX = Duration.ofHours(720);
  private static final Set<Integer> ALLOWED_HOURS = Set.of(2, 24, 168, 720);

  private GroupSessionTtl() {}

  public static Duration resolve(Integer ttlHours) {
    return ttlHours != null && ALLOWED_HOURS.contains(ttlHours)
        ? Duration.ofHours(ttlHours)
        : DEFAULT;
  }
}
