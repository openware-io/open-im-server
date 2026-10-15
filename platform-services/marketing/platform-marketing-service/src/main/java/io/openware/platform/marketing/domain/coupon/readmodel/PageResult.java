package io.openware.platform.marketing.domain.coupon.readmodel;

import java.util.List;

/**
 * 分页查询结果（领域侧）。
 *
 * <p>刻意**不**复用 MyBatis-Plus 的 {@code Page}/{@code IPage}：那会让领域端口暴露分页插件类型
 * （standards/10“端口命名与契约”明确禁止）。对外 JSON 形状由 API 层的视图类型决定。
 *
 * @param items    当前页数据
 * @param total    总条数
 * @param page     当前页号
 * @param pageSize 每页条数
 */
public record PageResult<T>(List<T> items, long total, long page, long pageSize) {

  /** 空页。 */
  public static <T> PageResult<T> empty(long page, long pageSize) {
    return new PageResult<>(List.of(), 0L, page, pageSize);
  }
}
