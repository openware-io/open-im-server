package io.openware.im.message.domain.message.repository;

import io.openware.im.message.domain.message.model.MessageFavorite;
import io.openware.im.message.domain.message.readmodel.MessageFavoriteView;
import java.util.List;

/**
 * 消息收藏仓储端口。
 *
 * <p><b>写路径返回聚合、读路径返回投影</b>（standards/10 L266-279）：收藏/取消收藏会改变状态，
 * 用 {@link MessageFavorite}；列表是只读聚合查询、结果不参与决策，用
 * {@link MessageFavoriteView} 投影——它携带收藏时刻，从而不必把该审计列塞进领域模型（L299）。
 *
 * <p>方法名体现「查询」语义（{@code searchFavorites}），与写路径的聚合获取区分（L279）。
 */
public interface MessageFavoriteRepository {
  /** 幂等收藏：命中 (user_id, msg_id) 唯一约束时返回 false，不重复插入。 */
  boolean saveIfAbsent(MessageFavorite favorite);

  /** 取消收藏：不存在时静默成功（幂等）。 */
  void deleteByUserIdAndMsgId(long userId, String msgId);

  /** 级联清理：消息被撤回/删除后，删除该消息的所有收藏记录。 */
  void deleteByMsgId(String msgId);

  /** 当前用户收藏分页（按收藏时间倒序）。 */
  FavoritePage searchFavorites(long userId, int page, int pageSize);

  record FavoritePage(List<MessageFavoriteView> items, long total) { }
}
