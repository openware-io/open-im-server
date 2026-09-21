package com.gvchat.infrastructure.security;

import com.gvchat.common.enums.UserRole;
import java.util.List;
import java.util.Collection;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security 用户主体，封装已认证用户的基本信息与权限。
 */
@Getter
public class SecurityUser implements UserDetails {
  private final Long id;
  private final String username;
  private final String password;
  private final UserRole role;
  private final boolean enabled;

  /**
   * 构造安全用户主体。
   *
   * @param id    用户 ID
   * @param username 登录用户。
   * @param password 密码哈希（可为占位，JWT 场景下通常不使用）
   * @param role   用户角色
   * @param enabled 账户是否启用
   */
  public SecurityUser(Long id, String username, String password, UserRole role, boolean enabled) {
    this.id = id;
    this.username = username;
    this.password = password;
    this.role = role;
    this.enabled = enabled;
  }

  /**
   * 返回 Spring Security 权限集合，角色映射为 {@code ROLE_*} 形式。
   *
   * @return 包含单一角色权限的不可变列表
   */
  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
  }

  /**
   * 账户是否未过期。
   *
   * @return 始终{@code true}
   */
  @Override
  public boolean isAccountNonExpired() { return true; }

  /**
   * 账户是否未锁定。
   *
   * @return 始终{@code true}
   */
  @Override
  public boolean isAccountNonLocked() { return true; }

  /**
   * 凭证是否未过期。
   *
   * @return 始终{@code true}
   */
  @Override
  public boolean isCredentialsNonExpired() { return true; }

  /**
   * 账户是否启用。
   *
   * @return 构造时传入的启用标。
   */
  @Override
  public boolean isEnabled() { return enabled; }
}
