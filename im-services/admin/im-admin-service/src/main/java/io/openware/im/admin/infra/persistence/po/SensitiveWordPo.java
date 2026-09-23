package io.openware.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.openware.common.enums.SensitiveWordCategory;
import io.openware.common.enums.SensitiveWordLevel;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_sensitive_word")
public class SensitiveWordPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String word;
  private SensitiveWordCategory category;
  private SensitiveWordLevel level;
  private Boolean enabled;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
