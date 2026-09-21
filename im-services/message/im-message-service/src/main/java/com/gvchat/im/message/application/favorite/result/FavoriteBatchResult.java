package com.gvchat.im.message.application.favorite.result;

import java.util.List;

/**
 * 批量收藏结果：items 与请求的 messageIds 一一对应（顺序一致、允许重复 id）。
 *
 * <p>created=false 表示该条未新建收藏：已收藏过（幂等命中），或消息不存在/无权访问/回查失败。
 * 单条失败不影响其余条目，也不会整批回滚。</p>
 */
public record FavoriteBatchResult(int created, int skipped, List<Item> items) {

  /** 单条消息的批量收藏处理结果。 */
  public record Item(String messageId, boolean created) { }
}
