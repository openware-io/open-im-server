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
@TableName("msg_outbox")
public class MessageOutboxPo {
  @TableId(type = IdType.AUTO) private Long id;
  @TableField("event_id") private String eventId;
  @TableField("aggregate_type") private String aggregateType;
  @TableField("aggregate_id") private String aggregateId;
  private String topic;
  @TableField("sharding_key") private String shardingKey;
  @TableField("payload_json") private String payloadJson;
  private Boolean published;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("published_at") private LocalDateTime publishedAt;
}
