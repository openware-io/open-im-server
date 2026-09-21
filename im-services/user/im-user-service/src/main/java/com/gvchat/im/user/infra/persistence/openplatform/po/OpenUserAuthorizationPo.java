package com.gvchat.im.user.infra.persistence.openplatform.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("open_user_authorization")
public class OpenUserAuthorizationPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("application_id")
  private Long applicationId;
  @TableField("user_id")
  private Long userId;
  @TableField("open_id")
  private String openId;
  private String scope;
  private String status;
  @TableField("authorized_at")
  private LocalDateTime authorizedAt;
  @TableField("revoked_at")
  private LocalDateTime revokedAt;
}
