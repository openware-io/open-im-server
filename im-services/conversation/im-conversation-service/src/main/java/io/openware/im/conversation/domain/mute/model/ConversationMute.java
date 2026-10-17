package io.openware.im.conversation.domain.mute.model;

/**
 * 用户对某个会话的免打扰设置；仅「已免打扰」的会话存在记录，取消免打扰即删除记录。
 *
 * <p>{@code created_at} 是记账列、不属于领域模型（standards/10 L299）：由适配器在写库时填写，
 * 本模型不携带也不需要它。
 */
public record ConversationMute(Long id, long userId, String conversationId) {

  public static ConversationMute create(long userId, String conversationId) {
    return new ConversationMute(null, userId, conversationId);
  }
}
