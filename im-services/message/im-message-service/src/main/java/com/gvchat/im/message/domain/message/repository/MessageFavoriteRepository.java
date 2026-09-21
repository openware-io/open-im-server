package com.gvchat.im.message.domain.message.repository;

import com.gvchat.im.message.domain.message.model.MessageFavorite;
import java.util.List;

public interface MessageFavoriteRepository {
  /** 幂等收藏：命中 (user_id, msg_id) 唯一约束时返回 false，不重复插入。 */
  boolean saveIfAbsent(MessageFavorite favorite);

  /** 取消收藏：不存在时静默成功（幂等）。 */
  void deleteByUserIdAndMsgId(long userId, String msgId);

  /** 级联清理：消息被撤回/删除后，删除该消息的所有收藏记录。 */
  void deleteByMsgId(String msgId);

  /** 当前用户收藏分页（按收藏时间倒序）。 */
  FavoritePage findByUserId(long userId, int page, int pageSize);

  record FavoritePage(List<MessageFavorite> items, long total) { }
}
