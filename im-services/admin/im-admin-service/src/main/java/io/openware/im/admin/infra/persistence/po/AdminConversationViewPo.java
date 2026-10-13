package io.openware.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_conversation_view")
public class AdminConversationViewPo {
  // 主键改为自增 `id`（`conversation_id` 降为唯一键 uk_adm_conversation_view_conversation_id）。
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("conversation_id")
  private String conversationId;
  private String conversationType;
  private Long ownerUserId;
  private String name;
  private String status;
  private Integer memberCount;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
