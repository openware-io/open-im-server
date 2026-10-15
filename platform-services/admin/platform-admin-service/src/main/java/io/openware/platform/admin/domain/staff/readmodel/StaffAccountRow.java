package io.openware.platform.admin.domain.staff.readmodel;

import io.openware.platform.admin.domain.model.ConsAdminAccount;
import java.time.LocalDateTime;

/**
 * 运营人员**列表读模型**（只读投影）。
 *
 * <p>按 standards/10“领域模型与只读投影的边界”，列表属于只读聚合查询，用**具名投影**承载，
 * 而不是把展示所需的字段塞回领域模型：{@code createdAt} 是记账时间，领域模型携带它会迫使领域层
 * 理解持久化语义。同一份数据的两种用途由两个类型表达。
 *
 * <p>投影允许携带审计列，这是规范对投影的放宽（只要求具名且不复用 PO）。
 *
 * @param account   账号聚合（业务事实）
 * @param createdAt 创建时间（记账列，仅供展示与核对）
 */
public record StaffAccountRow(ConsAdminAccount account, LocalDateTime createdAt) {

  /**
   * 该账号是否已绑定 IM。
   *
   * <p>由投影统一计算一次，避免每个调用方各自重复"非空且非空白"的判断。方法名用 {@code boundToIm}
   * 而不是暴露 {@code imAccount()} 的存在性，调用方不需要知道绑定是怎么表示的。
   */
  public boolean boundToIm() {
    return account.imAccount() != null && !account.imAccount().isBlank();
  }
}
