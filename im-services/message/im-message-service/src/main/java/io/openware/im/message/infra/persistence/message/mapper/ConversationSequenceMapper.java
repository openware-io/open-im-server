package io.openware.im.message.infra.persistence.message.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationSequenceMapper {
  /**
   * 幂等初始化会话序列行。
   *
   * <p>基线重建后本表主键为代理键 {@code id}，业务键 {@code conversation_id} 为唯一索引；
   * 4 个审计列均为 NOT NULL，故此处显式写入（{@code created_by}/{@code updated_by} 取 0 表示系统，
   * 时间列取数据库时钟），避免 MySQL 8 严格模式以 1364 拒插。
   */
  @Insert("""
      INSERT IGNORE INTO msg_conversation_sequence
        (conversation_id, last_seq, created_by, created_at, updated_by, updated_at)
      VALUES (#{conversationId}, 0, 0, NOW(3), 0, NOW(3))
      """)
  int initializeIfAbsent(@Param("conversationId") String conversationId);

  @Update("UPDATE msg_conversation_sequence SET last_seq = last_seq + 1 WHERE conversation_id = #{conversationId}")
  int increment(@Param("conversationId") String conversationId);

  @Select("SELECT last_seq FROM msg_conversation_sequence WHERE conversation_id = #{conversationId}")
  Long findLastSequence(@Param("conversationId") String conversationId);
}
