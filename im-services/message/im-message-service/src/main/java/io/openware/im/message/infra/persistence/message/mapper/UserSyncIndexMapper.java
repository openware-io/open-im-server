package io.openware.im.message.infra.persistence.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.message.infra.persistence.message.po.UserSyncIndexPo;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserSyncIndexMapper extends BaseMapper<UserSyncIndexPo> {
  /**
   * 幂等初始化用户同步序列行。
   *
   * <p>基线重建后本表主键为代理键 {@code id}，业务键 {@code user_id} 为唯一索引；
   * 4 个审计列均为 NOT NULL，故此处显式写入（{@code created_by}/{@code updated_by} 取 0 表示系统，
   * 时间列取数据库时钟），避免 MySQL 8 严格模式以 1364 拒插。
   */
  @Insert("""
      INSERT IGNORE INTO msg_user_sync_sequence
        (user_id, last_sync_seq, created_by, created_at, updated_by, updated_at)
      VALUES (#{userId}, 0, 0, NOW(3), 0, NOW(3))
      """)
  int initializeSequence(@Param("userId") long userId);

  @Select("SELECT last_sync_seq FROM msg_user_sync_sequence WHERE user_id = #{userId} FOR UPDATE")
  Long lockSequence(@Param("userId") long userId);

  @Update("UPDATE msg_user_sync_sequence SET last_sync_seq = #{nextSeq} WHERE user_id = #{userId}")
  int updateSequence(@Param("userId") long userId, @Param("nextSeq") long nextSeq);

  @Select("""
      SELECT user_id, sync_seq, msg_id, conversation_id, created_at
      FROM msg_user_sync_index
      WHERE user_id = #{userId} AND sync_seq > #{afterSyncSeq}
      ORDER BY sync_seq ASC
      LIMIT #{limit}
      """)
  List<UserSyncIndexPo> findAfter(@Param("userId") long userId, @Param("afterSyncSeq") long afterSyncSeq,
      @Param("limit") int limit);

  @Select("SELECT user_id FROM msg_user_sync_index WHERE msg_id = #{msgId} ORDER BY user_id")
  List<Long> findRecipientUserIdsByMsgId(@Param("msgId") String msgId);

  @Select({"<script>",
      "SELECT msg_id FROM msg_user_sync_index WHERE user_id = #{userId} AND msg_id IN",
      "<foreach collection='msgIds' item='msgId' open='(' separator=',' close=')'>#{msgId}</foreach>",
      "</script>"})
  List<String> findOwnedMessageIds(@Param("userId") long userId, @Param("msgIds") Collection<String> msgIds);

  @Delete("DELETE FROM msg_user_sync_index WHERE msg_id = #{msgId}")
  int deleteByMsgId(@Param("msgId") String msgId);

  @Delete("DELETE FROM msg_user_sync_sequence WHERE user_id = #{userId}")
  int deleteSequence(@Param("userId") long userId);
}
