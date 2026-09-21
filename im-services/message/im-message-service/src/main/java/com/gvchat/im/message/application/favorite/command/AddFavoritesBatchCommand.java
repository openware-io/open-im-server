package com.gvchat.im.message.application.favorite.command;

import com.gvchat.common.enums.ChatType;
import java.util.List;

/**
 * 批量收藏命令：peerId/chatType 对整批消息一致（同一会话多选收藏），messageIds 逐条独立处理。
 */
public record AddFavoritesBatchCommand(long userId, String peerId, ChatType chatType, List<String> messageIds) { }
