package com.gvchat.im.message.domain.message.model;

import java.util.List;

/**
 * @ 提及的存储语义。
 * <p>at_users 列（JSON）既存被 @ 用户的 id（字符串），也支持用固定标记表示 @所有人。
 * 真实用户 id 恒为正整数，0 仅表示系统/内部，不会与任何成员冲突，因此用 "0" 作为 @all 的稳定标记。
 */
public final class Mention {
  /** @all（@所有人）特殊标记：目标 id=0。 */
  public static final String AT_ALL = "0";

  private Mention() {
  }

  /** 判断 at_users 是否包含 @all 标记。 */
  public static boolean isAtAll(List<String> atUsers) {
    return atUsers != null && atUsers.contains(AT_ALL);
  }
}
