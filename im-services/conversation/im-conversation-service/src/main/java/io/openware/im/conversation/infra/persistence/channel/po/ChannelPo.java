package io.openware.im.conversation.infra.persistence.channel.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("conversation_channel")
public class ChannelPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String code;
  @TableField("owner_id")
  private Long ownerId;
  private String name;
  private String avatar;
  private String announcement;
  @TableField("discussion_group_id")
  private Long discussionGroupId;
  private String status;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
