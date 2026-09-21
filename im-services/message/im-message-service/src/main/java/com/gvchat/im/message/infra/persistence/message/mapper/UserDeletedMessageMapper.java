package com.gvchat.im.message.infra.persistence.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.message.infra.persistence.message.po.UserDeletedMessagePo;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserDeletedMessageMapper extends BaseMapper<UserDeletedMessagePo> {

  /** 幂等写入「删除仅我」墓碑（重复删除同一条消息不报错）。 */
  @Insert("""
      INSERT IGNORE INTO msg_user_deleted_message (user_id, msg_id, conversation_id, chat_type, created_at)
      VALUES (#{userId}, #{msgId}, #{conversationId}, #{chatType}, NOW(3))
      """)
  int insertIgnore(@Param("userId") long userId, @Param("msgId") String msgId,
      @Param("conversationId") String conversationId, @Param("chatType") String chatType);

  /** 批量删除该用户在这些消息上的墓碑（用于清空会话时一并回收）。 */
  @org.apache.ibatis.annotations.Delete("""
      DELETE FROM msg_user_deleted_message WHERE user_id = #{userId} AND conversation_id = #{conversationId}
      """)
  int deleteByConversation(@Param("userId") long userId, @Param("conversationId") String conversationId);

  /**
   * 该用户名下全部「删除仅我」墓碑（含会话定位信息）。
   *
   * <p>必须带 conversation_id/chat_type：客户端在**未打开该会话**时内存里没有消息，
   * 只靠 msgId 无法定位要清理的会话预览（真机实测到的缺陷）。
   */
  @Select("""
      SELECT msg_id, conversation_id, chat_type FROM msg_user_deleted_message WHERE user_id = #{userId}
      ORDER BY created_at DESC LIMIT 2000
      """)
  List<UserDeletedMessageRow> findAllByUserId(@Param("userId") long userId);

  /** 「删除仅我」墓碑行（同步下发用）。 */
  class UserDeletedMessageRow {
    private String msgId;
    private String conversationId;
    private String chatType;
    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
  }

  /** 查询该用户在给定消息集合中已删除的部分（结果集较小，供服务层过滤）。 */
  @Select("""
      <script>
      SELECT msg_id FROM msg_user_deleted_message
      WHERE user_id = #{userId} AND msg_id IN
      <foreach collection="msgIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      </script>
      """)
  List<String> findDeletedMsgIds(@Param("userId") long userId, @Param("msgIds") List<String> msgIds);
}
