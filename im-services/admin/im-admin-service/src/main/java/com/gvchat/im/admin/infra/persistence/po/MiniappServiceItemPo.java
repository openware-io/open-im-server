package com.gvchat.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_miniapp_service_item")
public class MiniappServiceItemPo {
  @TableId(type = IdType.AUTO)
  private Integer id;
  @TableField("type_id")
  private Integer typeId;
  private String name;
  private String link;
  private String introduction;
  private String icon;
  private Boolean status;
  @TableField("is_top")
  private Boolean isTop;
  private String audience;
  /** 1=隐藏：列表不展示，仍可搜索、可固定。 */
  private Boolean hidden;
  @TableField("sort_order")
  private Integer sortOrder;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
