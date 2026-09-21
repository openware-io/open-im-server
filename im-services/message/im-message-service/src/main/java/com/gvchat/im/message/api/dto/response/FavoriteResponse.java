package com.gvchat.im.message.api.dto.response;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgType;
import java.time.Instant;

/**
 * 收藏列表单项：消息被撤回/删除时 msgType/content/senderId/senderUsername/createdAt 为 null。
 */
public record FavoriteResponse(String msgId, String peerId, ChatType chatType, MsgType msgType, String content,
    Long senderId, String senderUsername, Instant createdAt, Instant favoritedAt) { }
