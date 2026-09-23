package io.openware.im.user.infra.persistence.account.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user")
public class UserAccountPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String username;
  private String nickname;
  private String avatar;
  private String password;
  private String email;
  private String phone;
  private String signature;
  private String status;
  @TableField("status_version")
  private Long statusVersion;
  @TableField("self_destruct_policy")
  private String selfDestructPolicy;
  @TableField("self_destruct_at")
  private LocalDateTime selfDestructAt;
  @TableField("last_login_at")
  private LocalDateTime lastLoginAt;
  private String role;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
