package com.gvchat.im.message.application.query;

import com.gvchat.common.enums.ChatType;

/** 历史查询：分页（page/pageSize）、按日期（date，yyyy-MM-dd）或以中心消息定位窗口（centerMsgId + before/after）。 */
public record MessageHistoryQuery(long userId, String peerId, ChatType chatType, Integer page, Integer pageSize,
    String centerMsgId, Integer beforeCount, Integer afterCount, String date) {

  public MessageHistoryQuery(long userId, String peerId, ChatType chatType, Integer page, Integer pageSize) {
    this(userId, peerId, chatType, page, pageSize, null, null, null, null);
  }
}
