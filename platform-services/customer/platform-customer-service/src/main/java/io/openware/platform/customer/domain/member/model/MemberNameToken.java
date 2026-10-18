package io.openware.platform.customer.domain.member.model;

/**
 * 客户姓名/客户号盲索引 token（{@code cst_member_name_token}）。
 *
 * <p>{@code token} = 归一化文本的 1/2/3-gram 的 SHA-256 十六进制；检索要求关键词的所有 token 都命中（AND）。
 * 与 {@code cst_member} 的关系是「一个客户 N 行 token」，重建时<b>先删后插</b>（天然幂等）。
 *
 * <p>字段集只来自真实 DDL 列：{@code id / tenant_id / member_id / token}。
 * 该表在 DDL 上有 {@code created_by / updated_by / created_at / updated_at} 四个审计列，
 * 写入方不写它们（取库默认值），因此领域模型不承载——与 {@code WalletAccount} / {@code PointAccount} 同一约定。
 *
 * @param id       行标识
 * @param tenantId 租户
 * @param memberId 所属客户档案
 * @param token    归一化 gram 的 SHA-256 十六进制（定长 64）
 */
public record MemberNameToken(
    Long id,
    Long tenantId,
    Long memberId,
    String token) {

  /** 新 token 行（待插入）。 */
  public static MemberNameToken of(Long tenantId, Long memberId, String token) {
    return new MemberNameToken(null, tenantId, memberId, token);
  }
}
