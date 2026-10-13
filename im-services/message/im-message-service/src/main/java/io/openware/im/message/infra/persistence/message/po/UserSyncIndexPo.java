package io.openware.im.message.infra.persistence.message.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("msg_user_sync_index")
public class UserSyncIndexPo {
  /**
   * 基线重建（批次 2）后本表主键改为代理键 {@code id}，原复合主键 {@code (user_id, sync_seq)}
   * 降级为唯一索引 {@code uk_msg_user_sync_index_user_seq}。业务键字段名保持不变，
   * 所有查询仍按 {@code user_id}/{@code sync_seq}/{@code msg_id} 定位，未使用 {@code selectById}。
   */
  @TableId(type = IdType.AUTO) private Long id;
  @TableField("user_id") private Long userId;
  @TableField("sync_seq") private Long syncSeq;
  @TableField("msg_id") private String msgId;
  @TableField("conversation_id") private String conversationId;
  @TableField("created_by") private Long createdBy;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_by") private Long updatedBy;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
