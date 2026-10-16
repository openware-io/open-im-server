package io.openware.platform.tenant.domain.approval.repository;

import io.openware.platform.tenant.domain.approval.IamApproval;
import java.util.List;
import java.util.Optional;

/**
 * IAM 高风险动作复核单仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface IamApprovalRepository {

  /**
   * 按幂等键读取复核单（提交用例的幂等判据）。
   *
   * <p>该键上的唯一性由数据库约束保证，因此最多返回一条。
   */
  Optional<IamApproval> findByIdempotencyKey(String idempotencyKey);

  /** 按标识读取复核单。 */
  Optional<IamApproval> findById(Long id);

  /** 保存复核单：{@code id} 为空时新增，否则更新，返回落库后的聚合。 */
  IamApproval save(IamApproval approval);

  /**
   * 列出复核单（按创建时间倒序），可按动作类型与状态过滤。
   *
   * <p>过滤与排序是查询语义，收在端口里；{@code null} 或空白表示该条件不筛。
   */
  List<IamApproval> list(String actionType, String status);
}
