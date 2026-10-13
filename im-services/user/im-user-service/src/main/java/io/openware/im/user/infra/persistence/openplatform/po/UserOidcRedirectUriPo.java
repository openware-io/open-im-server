package io.openware.im.user.infra.persistence.openplatform.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("user_oidc_redirect_uri")
public class UserOidcRedirectUriPo {
  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("client_id")
  private String clientId;

  @TableField("redirect_uri")
  private String redirectUri;

  @TableField("created_by")
  private Long createdBy;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_by")
  private Long updatedBy;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
