package com.gvchat.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 通用分页查询参数（page、pageSize），供各列表 API 复用*/
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaginationDto {
  private Integer page;
  private Integer pageSize;
}
