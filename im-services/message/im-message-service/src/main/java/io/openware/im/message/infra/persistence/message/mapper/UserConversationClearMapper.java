package io.openware.im.message.infra.persistence.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.message.infra.persistence.message.po.UserConversationClearPo;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserConversationClearMapper extends BaseMapper<UserConversationClearPo> {

  /** 幂等写入/更新清空时刻（同一用户同一会话保留最新一次清空）。 */
  @Insert("""
      INSERT INTO msg_user_conversation_clear (user_id, conversation_id, chat_type, cleared_at, cleared_by, created_at)
      VALUES (#{userId}, #{conversationId}, #{chatType}, #{clearedAt}, #{clearedBy}, NOW(3))
      ON DUPLICATE KEY UPDATE cleared_at = VALUES(cleared_at), cleared_by = VALUES(cleared_by), chat_type = VALUES(chat_type)
      """)
  int upsert(@Param("userId") long userId, @Param("conversationId") String conversationId,
      @Param("chatType") String chatType, @Param("clearedAt") java.time.LocalDateTime clearedAt,
      @Param("clearedBy") long clearedBy);

  /** 该用户名下全部清空标记：客户端每次同步都拉取，用于自愈离线期间丢失的清理。 */
  @Select("""
      SELECT conversation_id, chat_type, cleared_at FROM msg_user_conversation_clear
      WHERE user_id = #{userId} ORDER BY cleared_at DESC LIMIT 500
      """)
  List<UserConversationClearPo> findByUserId(@Param("userId") long userId);
}
