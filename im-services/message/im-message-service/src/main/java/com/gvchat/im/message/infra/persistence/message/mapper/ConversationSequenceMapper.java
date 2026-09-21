package com.gvchat.im.message.infra.persistence.message.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationSequenceMapper {
  @Insert("INSERT IGNORE INTO msg_conversation_sequence (conversation_id, last_seq) VALUES (#{conversationId}, 0)")
  int initializeIfAbsent(@Param("conversationId") String conversationId);

  @Update("UPDATE msg_conversation_sequence SET last_seq = last_seq + 1 WHERE conversation_id = #{conversationId}")
  int increment(@Param("conversationId") String conversationId);

  @Select("SELECT last_seq FROM msg_conversation_sequence WHERE conversation_id = #{conversationId}")
  Long findLastSequence(@Param("conversationId") String conversationId);
}
