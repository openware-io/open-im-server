package io.openware.im.message.domain.message.repository;

/**
 * 「业务聚合 + 记账快照」的读回结果。
 *
 * <p><b>为什么读路径要单独带审计值</b>：领域模型不承载审计列（standards/10 §聚合装配与持久化契约），
 * 而响应契约（{@code toResult}）要回显 {@code createdBy/createdAt/updatedBy/updatedAt}，
 * 编辑窗口规则还要用 {@code createdAt} 作为发送时刻。端口把两者分开交给上层，而不是把审计列塞回领域模型：
 * 只需要业务事实的调用方读 {@link #aggregate()}，要装配响应或做窗口判定的调用方再读 {@link #audit()}。
 *
 * <p>与 {@code io.openware.im.message.domain.secretmessage.repository.Persisted} 同形——
 * 各端口自带自己的包装类型，不做跨域共享（共享会让一个域的调整牵动另一个域）。
 *
 * @param aggregate 业务聚合
 * @param audit     该行的记账快照；新建未落库时为 {@code null}
 */
public record Persisted<T>(T aggregate, AuditSnapshot audit) {

  /** 只取业务聚合（调用方不关心记账值时用；{@code null} 安全）。 */
  public static <T> T aggregateOf(Persisted<T> row) {
    return row == null ? null : row.aggregate();
  }
}
