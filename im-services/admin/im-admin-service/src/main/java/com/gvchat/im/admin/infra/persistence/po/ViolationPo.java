package com.gvchat.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gvchat.common.enums.ViolationAction;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_violation")
public class ViolationPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("user_id")
  private Long userId;
  private String reason;
  private String content;
  @TableField("msg_id")
  private String msgId;
  private ViolationAction action;
  private String remark;
  @TableField("created_at")
  private LocalDateTime createdAt;
}
