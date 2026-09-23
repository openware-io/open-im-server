package io.openware.im.user.infra.persistence.openplatform.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_oidc_redirect_uri")
public class UserOidcRedirectUriPo {
  @TableId
  private Long id;

  @TableField("client_id")
  private String clientId;

  @TableField("redirect_uri")
  private String redirectUri;
}
