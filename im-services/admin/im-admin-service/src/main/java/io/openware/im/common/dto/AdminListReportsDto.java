package io.openware.common.dto;

import io.openware.common.enums.ReportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 管理端举报列表查询参数，供管理端举报审核 API 使用*/
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminListReportsDto {
  private ReportStatus status;
  private Integer page;
  private Integer pageSize;
}
