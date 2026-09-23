package io.openware.im.message.infra.persistence.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.message.infra.persistence.message.po.MessagePo;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<MessagePo> {
  @Select("""
      SELECT COUNT(*)
      FROM msg_user_sync_index sync
      JOIN msg_message message ON message.msg_id = sync.msg_id
      LEFT JOIN msg_read_status read_status
        ON read_status.msg_id = message.msg_id AND read_status.user_id = sync.user_id
      WHERE sync.user_id = #{userId}
        AND message.from_user_id != #{userId}
        AND read_status.id IS NULL
        AND NOT EXISTS (SELECT 1 FROM msg_user_deleted_message deleted
          WHERE deleted.user_id = sync.user_id AND deleted.msg_id = message.msg_id)
        AND message.msg_type <> 'SYSTEM'
        AND ((message.chat_type = 'PRIVATE' AND #{privateEnabled})
          OR (message.chat_type = 'GROUP' AND #{groupEnabled})
          OR (message.chat_type = 'CHANNEL' AND #{channelEnabled}))
      """)
  long countUnreadForUser(@Param("userId") long userId, @Param("privateEnabled") boolean privateEnabled,
      @Param("groupEnabled") boolean groupEnabled, @Param("channelEnabled") boolean channelEnabled);

  @Select("""
      SELECT sync.conversation_id AS conversation_id, COUNT(*) AS cnt
      FROM msg_user_sync_index sync
      JOIN msg_message message ON message.msg_id = sync.msg_id
      LEFT JOIN msg_read_status read_status
        ON read_status.msg_id = message.msg_id AND read_status.user_id = sync.user_id
      WHERE sync.user_id = #{userId}
        AND message.from_user_id != #{userId}
        AND read_status.id IS NULL
        AND NOT EXISTS (SELECT 1 FROM msg_user_deleted_message deleted
          WHERE deleted.user_id = sync.user_id AND deleted.msg_id = message.msg_id)
        AND message.msg_type <> 'SYSTEM'
        AND ((message.chat_type = 'PRIVATE' AND #{privateEnabled})
          OR (message.chat_type = 'GROUP' AND #{groupEnabled})
          OR (message.chat_type = 'CHANNEL' AND #{channelEnabled}))
      GROUP BY sync.conversation_id
      """)
  List<Map<String, Object>> countUnreadByConversationForUser(@Param("userId") long userId,
      @Param("privateEnabled") boolean privateEnabled, @Param("groupEnabled") boolean groupEnabled,
      @Param("channelEnabled") boolean channelEnabled);

  /*
   * 清空会话时的关联行清理。
   *
   * 顺序要求：必须在删除 msg_message 之前调用（子查询依赖消息行仍然存在），否则会留下
   * 指向已删消息的孤儿行（同步索引孤儿会拖慢同步分页、已读孤儿会长期堆积）。
   * 2026-09-10 修复：此前 clear-private / clear-group 只删 msg_message，已积累 3991 条同步索引
   * 孤儿与 2779 条已读孤儿。
   */

  @Delete("""
      DELETE FROM msg_message WHERE chat_type = 'PRIVATE'
        AND ((from_user_id = #{userId} AND to_id = #{peerId}) OR (from_user_id = #{peerId} AND to_id = #{userId})
            OR (from_user_id = 0 AND to_id IN (#{userId}, #{peerId})))
      """)
  int deleteMessagesInPrivateChat(@Param("userId") long userId, @Param("peerId") long peerId);

  @Delete("""
      DELETE FROM msg_message WHERE chat_type = 'GROUP' AND to_id = #{groupId}
      """)
  int deleteMessagesInGroupChat(@Param("groupId") long groupId);

  @Delete("""
      DELETE FROM msg_read_status WHERE msg_id IN (
        SELECT msg_id FROM msg_message WHERE chat_type = 'PRIVATE'
          AND ((from_user_id = #{userId} AND to_id = #{peerId}) OR (from_user_id = #{peerId} AND to_id = #{userId})
            OR (from_user_id = 0 AND to_id IN (#{userId}, #{peerId}))))
      """)
  int deleteReadStatusInPrivateChat(@Param("userId") long userId, @Param("peerId") long peerId);

  @Delete("""
      DELETE FROM msg_user_sync_index WHERE msg_id IN (
        SELECT msg_id FROM msg_message WHERE chat_type = 'PRIVATE'
          AND ((from_user_id = #{userId} AND to_id = #{peerId}) OR (from_user_id = #{peerId} AND to_id = #{userId})
            OR (from_user_id = 0 AND to_id IN (#{userId}, #{peerId}))))
      """)
  int deleteSyncIndexInPrivateChat(@Param("userId") long userId, @Param("peerId") long peerId);

  @Delete("""
      DELETE FROM msg_message_favorite WHERE msg_id IN (
        SELECT msg_id FROM msg_message WHERE chat_type = 'PRIVATE'
          AND ((from_user_id = #{userId} AND to_id = #{peerId}) OR (from_user_id = #{peerId} AND to_id = #{userId})
            OR (from_user_id = 0 AND to_id IN (#{userId}, #{peerId}))))
      """)
  int deleteFavoriteInPrivateChat(@Param("userId") long userId, @Param("peerId") long peerId);

  @Delete("""
      DELETE FROM msg_read_status WHERE msg_id IN (
        SELECT msg_id FROM msg_message WHERE chat_type = 'GROUP' AND to_id = #{groupId})
      """)
  int deleteReadStatusInGroupChat(@Param("groupId") long groupId);

  @Delete("""
      DELETE FROM msg_user_sync_index WHERE msg_id IN (
        SELECT msg_id FROM msg_message WHERE chat_type = 'GROUP' AND to_id = #{groupId})
      """)
  int deleteSyncIndexInGroupChat(@Param("groupId") long groupId);

  @Delete("""
      DELETE FROM msg_message_favorite WHERE msg_id IN (
        SELECT msg_id FROM msg_message WHERE chat_type = 'GROUP' AND to_id = #{groupId})
      """)
  int deleteFavoriteInGroupChat(@Param("groupId") long groupId);
}
