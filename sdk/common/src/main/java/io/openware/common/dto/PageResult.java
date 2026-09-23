package io.openware.common.dto;

import java.util.List;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 通用分页响应包装，供各管理端与用户端列表 API 返回*/
@Getter
@Builder
@AllArgsConstructor
public class PageResult<T> {
  private List<T> items;
  private long total;
  private int page;
  private int pageSize;
  @Builder.Default
  private Instant updatedAt = Instant.now();
}
