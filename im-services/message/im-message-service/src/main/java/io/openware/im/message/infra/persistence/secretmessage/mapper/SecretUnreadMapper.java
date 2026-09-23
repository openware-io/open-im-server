package io.openware.im.message.infra.persistence.secretmessage.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SecretUnreadMapper {
  @Insert("""
      INSERT IGNORE INTO msg_secret_unread(chat_type, conversation_id, user_id, msg_id, seq, created_at)
      VALUES(#{chatType}, #{conversationId}, #{userId}, #{msgId}, #{seq}, #{occurredAt})
      """)
  void insert(@Param("chatType") String chatType, @Param("conversationId") long conversationId,
      @Param("userId") long userId, @Param("msgId") String msgId, @Param("seq") long seq,
      @Param("occurredAt") LocalDateTime occurredAt);

  @Delete("""
      DELETE FROM msg_secret_unread
      WHERE chat_type = #{chatType} AND conversation_id = #{conversationId} AND user_id = #{userId}
        AND seq <= #{afterSeq}
      """)
  void markRead(@Param("chatType") String chatType, @Param("conversationId") long conversationId,
      @Param("userId") long userId, @Param("afterSeq") long afterSeq);

  @Delete("""
      DELETE FROM msg_secret_unread
      WHERE chat_type = #{chatType} AND conversation_id = #{conversationId} AND msg_id = #{msgId}
      """)
  void deleteByMessage(@Param("chatType") String chatType, @Param("conversationId") long conversationId,
      @Param("msgId") String msgId);

  @Select("""
      SELECT COUNT(*) FROM msg_secret_unread
      WHERE user_id = #{userId}
        AND ((chat_type = 'secret' AND #{secretChatEnabled})
          OR (chat_type = 'secret_group' AND #{secretGroupChatEnabled}))
      """)
  long countForUser(@Param("userId") long userId, @Param("secretChatEnabled") boolean secretChatEnabled,
      @Param("secretGroupChatEnabled") boolean secretGroupChatEnabled);

  @Select("""
      SELECT CONCAT(chat_type, ':', conversation_id) AS conversation_id, COUNT(*) AS cnt
      FROM msg_secret_unread
      WHERE user_id = #{userId}
        AND ((chat_type = 'secret' AND #{secretChatEnabled})
          OR (chat_type = 'secret_group' AND #{secretGroupChatEnabled}))
      GROUP BY chat_type, conversation_id
      """)
  List<Map<String, Object>> countByConversationForUser(@Param("userId") long userId,
      @Param("secretChatEnabled") boolean secretChatEnabled,
      @Param("secretGroupChatEnabled") boolean secretGroupChatEnabled);
}
