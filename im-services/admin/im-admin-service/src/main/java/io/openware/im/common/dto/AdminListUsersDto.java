package io.openware.common.dto;

import io.openware.common.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminListUsersDto {
  private String username;
  private String keyword;
  private UserStatus status;
  private Integer page;
  private Integer pageSize;
}
