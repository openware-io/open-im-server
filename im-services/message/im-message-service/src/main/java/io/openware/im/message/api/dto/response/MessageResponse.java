package io.openware.im.message.api.dto.response;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgStatus;
import io.openware.common.enums.MsgType;
import java.time.Instant;
import java.util.List;
import io.openware.protocol.mq.event.MessageMedia;

public record MessageResponse(Long id, String msgId, String conversationId, long seq, long fromUserId,
    String senderUsername, String toId, ChatType chatType, MsgType msgType, String content, String clientMsgId,
    String replyMsgId, List<String> atUsers, MsgStatus status, boolean edited, Instant editedAt, Long createdBy,
    Instant createdAt, Long updatedBy, Instant updatedAt, List<MessageMedia> media) { }
