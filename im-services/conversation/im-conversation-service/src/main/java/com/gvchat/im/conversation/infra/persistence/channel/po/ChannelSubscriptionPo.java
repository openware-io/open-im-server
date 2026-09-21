package com.gvchat.im.conversation.infra.persistence.channel.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("conversation_channel_subscription")
public class ChannelSubscriptionPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("channel_id")
  private Long channelId;
  @TableField("user_id")
  private Long userId;
  @TableField("notify_setting")
  private String notifySetting;
  @TableField("joined_at")
  private LocalDateTime joinedAt;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
