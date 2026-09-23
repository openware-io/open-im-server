package io.openware.im.message.infra.persistence.message;

import io.openware.im.message.domain.message.model.Message;
import io.openware.im.message.domain.message.model.MessageFavorite;
import io.openware.im.message.domain.message.model.MessageOutbox;
import io.openware.im.message.infra.persistence.message.po.MessageFavoritePo;
import io.openware.im.message.infra.persistence.message.po.MessageOutboxPo;
import io.openware.im.message.infra.persistence.message.po.MessagePo;

public final class MessagePersistenceConverter {
  private MessagePersistenceConverter() { }

  public static MessagePo toPo(Message message) {
    MessagePo po = new MessagePo();
    po.setId(message.getId()); po.setMsgId(message.getMsgId()); po.setConversationId(message.getConversationId());
    po.setSeq(message.getSeq()); po.setFromUserId(message.getFromUserId()); po.setSenderUsername(message.getSenderUsername());
    po.setToId(message.getToId()); po.setChatType(message.getChatType()); po.setMsgType(message.getMsgType());
    po.setContent(message.getContent()); po.setClientMsgId(message.getClientMsgId()); po.setReplyMsgId(message.getReplyMsgId());
    po.setAtUsers(message.getAtUsers()); po.setStatus(message.getStatus()); po.setCreatedBy(message.getCreatedBy());
    po.setMediaObjectIds(message.getMediaObjectIds());
    po.setEdited(message.isEdited()); po.setEditedAt(message.getEditedAt());
    po.setCreatedAt(message.getCreatedAt()); po.setUpdatedBy(message.getUpdatedBy()); po.setUpdatedAt(message.getUpdatedAt());
    return po;
  }

  public static Message toDomain(MessagePo po) {
    return Message.restore(po.getId(), po.getMsgId(), po.getConversationId(), po.getSeq(), po.getFromUserId(),
        po.getSenderUsername(), po.getToId(), po.getChatType(), po.getMsgType(), po.getContent(),
        po.getClientMsgId(), po.getReplyMsgId(), po.getAtUsers(), po.getMediaObjectIds(), po.getStatus(),
        Boolean.TRUE.equals(po.getEdited()), po.getEditedAt(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(),
        po.getUpdatedAt());
  }

  public static MessageOutboxPo toPo(MessageOutbox outbox) {
    MessageOutboxPo po = new MessageOutboxPo();
    po.setId(outbox.getId()); po.setEventId(outbox.getEventId()); po.setAggregateType(outbox.getAggregateType());
    po.setAggregateId(outbox.getAggregateId()); po.setTopic(outbox.getTopic()); po.setShardingKey(outbox.getShardingKey());
    po.setPayloadJson(outbox.getPayloadJson()); po.setPublished(outbox.isPublished()); po.setCreatedAt(outbox.getCreatedAt());
    po.setPublishedAt(outbox.getPublishedAt());
    return po;
  }

  public static MessageOutbox toDomain(MessageOutboxPo po) {
    return MessageOutbox.restore(po.getId(), po.getEventId(), po.getAggregateType(), po.getAggregateId(), po.getTopic(),
        po.getShardingKey(), po.getPayloadJson(), Boolean.TRUE.equals(po.getPublished()), po.getCreatedAt(), po.getPublishedAt());
  }

  public static MessageFavoritePo toPo(MessageFavorite favorite) {
    MessageFavoritePo po = new MessageFavoritePo();
    po.setId(favorite.getId()); po.setUserId(favorite.getUserId()); po.setMsgId(favorite.getMsgId());
    po.setPeerId(favorite.getPeerId()); po.setChatType(favorite.getChatType()); po.setCreatedAt(favorite.getCreatedAt());
    po.setMsgTypeSnapshot(favorite.getMsgTypeSnapshot()); po.setContentSnapshot(favorite.getContentSnapshot());
    po.setFromUserIdSnapshot(favorite.getFromUserIdSnapshot());
    po.setSenderUsernameSnapshot(favorite.getSenderUsernameSnapshot());
    po.setSentAtSnapshot(favorite.getSentAtSnapshot());
    return po;
  }

  public static MessageFavorite toDomain(MessageFavoritePo po) {
    return MessageFavorite.restore(po.getId(), po.getUserId(), po.getMsgId(), po.getPeerId(), po.getChatType(),
        po.getCreatedAt(), po.getMsgTypeSnapshot(), po.getContentSnapshot(), po.getFromUserIdSnapshot(),
        po.getSenderUsernameSnapshot(), po.getSentAtSnapshot());
  }
}
