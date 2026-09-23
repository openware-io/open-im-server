package io.openware.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_user_view")
public class AdminUserViewPo {
  @TableId
  private Long userId;
  private String username;
  private String nickname;
  private String avatar;
  private String email;
  private String phone;
  private String signature;
  private String status;
  private long statusVersion;
  private String role;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
