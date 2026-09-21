package com.gvchat.im.message.domain.message.port;

import java.util.List;

/**
 * @ 提及解析端口：把发送方提交的 @ 目标解析为确定的通知目标。
 * <p>解析优先级遵循产品语义：群昵称优先，无群昵称回退个人昵称；
 * "@all"（@所有人）解析为全体成员，并以 mentionsAll 标记返回。
 * 数字目标视为已由客户端解析完成的用户 id，原样透传。
 */
public interface MentionResolverPort {
  /**
   * 解析群聊消息的 @ 目标。
   *
   * @param groupId 群 id
   * @param targets 原始 @ 目标列表（可能是用户 id、"@all"、群昵称或个人信息昵称）
   * @return 解析结果：mentionsAll 表示是否 @所有人；resolvedUserIds 为解析后的成员用户 id（去重、有序）
   */
  MentionResolution resolveGroupMentions(long groupId, List<String> targets);

  record MentionResolution(boolean mentionsAll, List<Long> resolvedUserIds) {
    public static MentionResolution empty() {
      return new MentionResolution(false, List.of());
    }
  }
}
