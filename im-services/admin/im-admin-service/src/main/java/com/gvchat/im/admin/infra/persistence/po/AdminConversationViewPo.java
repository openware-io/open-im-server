package com.gvchat.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_conversation_view")
public class AdminConversationViewPo {
  @TableId
  private String conversationId;
  private String conversationType;
  private Long ownerUserId;
  private String name;
  private String status;
  private Integer memberCount;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
