package io.openware.group.idaas.infra.persistence.po;

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

  /** 审计字段：写入方未赋值时由 MyBatis-Plus 省略该列，取库默认值 */
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;
}
