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
@TableName("adm_miniapp_service_type")
public class MiniappServiceTypePo {
  @TableId(type = IdType.AUTO)
  private Integer id;
  private String name;
  @TableField("sort_order")
  private Integer sortOrder;
  /** 1=隐藏：该分组不展示，组内小程序仍可搜索、可固定。 */
  private Boolean hidden;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
