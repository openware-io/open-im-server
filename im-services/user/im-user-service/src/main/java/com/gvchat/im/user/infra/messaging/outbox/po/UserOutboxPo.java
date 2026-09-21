package com.gvchat.im.user.infra.messaging.outbox.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_outbox")
public class UserOutboxPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("event_id")
  private String eventId;
  @TableField("aggregate_type")
  private String aggregateType;
  @TableField("aggregate_id")
  private String aggregateId;
  private String topic;
  @TableField("sharding_key")
  private String shardingKey;
  @TableField("payload_json")
  private String payloadJson;
  private Boolean published;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("published_at")
  private LocalDateTime publishedAt;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
