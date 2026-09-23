package io.openware.im.admin.domain.miniapp;

import java.time.LocalDateTime;

/**
 * 小程序服务项。
 *
 * @param hidden true=不在「服务」列表展示（含所属分组被隐藏的情况），但仍可被搜索到、
 *               仍可固定到快捷应用区。与 {@code audience} 相互独立：audience 表达面向谁，
 *               hidden 表达是否出现在列表里。
 */
public record MiniappServiceItem(Integer id, Integer typeId, String name, String link, String introduction,
                                 String icon, Boolean status, Boolean isTop, String audience, Boolean hidden,
                                 Integer sortOrder, LocalDateTime createdAt, LocalDateTime updatedAt) {
  /** 面向对象：消费者服务（固定展示 + 可搜索）。 */
  public static final String AUDIENCE_CONSUMER = "consumer";
  /** 面向对象：运营后台（仅搜索展示，不进固定展示）。 */
  public static final String AUDIENCE_OPERATOR = "operator";

  /** 展示层是否隐藏：null 视为不隐藏，保证历史数据（无该字段）行为不变。 */
  public boolean hiddenFlag() {
    return Boolean.TRUE.equals(hidden);
  }
}
