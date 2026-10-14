package io.openware.group.idaas.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 集团组织（当前仅一级，parentId 暂不使用）。
 */
@Getter
@Setter
@TableName("idaas_org")
public class IdaasOrgPo {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;
  private String code;

  /** 父组织ID（当前仅一级，可空暂不使用） */
  private Long parentId;

  /** 状态 ENABLED/DISABLED */
  private String status;

  /** 审计字段：写入方未赋值时由 MyBatis-Plus 省略该列，取库默认值 */
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;
}
