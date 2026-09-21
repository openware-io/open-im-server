package com.gvchat.group.idaas.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 集团管理员账号（统一登录主体）。
 */
@Getter
@Setter
@TableName("group_account")
public class GroupAccountPo {
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 用户名（唯一） */
  private String username;

  /** BCrypt 密码哈希 */
  private String password;

  /** 显示名 */
  private String displayName;

  /** 状态 ENABLED/DISABLED */
  private String status;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
