package com.gvchat.im.user.infra.persistence.openplatform.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("open_application_scope")
public class OpenApplicationScopePo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("application_id")
  private Long applicationId;
  private String scope;
}
