package io.openware.platform.customer.api.dto;

import io.openware.platform.customer.application.MemberDisplay;
import io.openware.platform.customer.domain.member.model.Member;
import java.time.LocalDateTime;

/**
 * 客户档案响应的对外视图。
 *
 * <p><b>字段集是刻意"照搬"的</b>：{@code GET /business/members} 此前直接返回持久化对象
 * {@code CstMemberPo}，所以 {@code tenantId / nameCipher / phoneCipher / phoneDigest / levelId /
 * version / createdBy / createdAt / updatedBy / updatedAt} 一直是响应的一部分（{@code POST /business/members}
 * 与 {@code PUT /business/members/{id}/im-binding} 也返回它）。删字段或改名是一次 API 破坏
 * （B 端与 C 端可能已在读），因此这里<b>逐个保留字段名</b>；按 standards/10 D2 审计列本不该出现在契约里，
 * 收紧需要单独一次带版本说明的变更。
 *
 * <p>非持久化的展示字段（{@code name / phone / accountType / imAccountName / imAccountDeleted}）
 * 同样保留：它们是 PO 上 {@code @TableField(exist = false)} 的回显字段，但<b>确实是既有响应字段</b>
 * ——它们只出现在视图里，绝不会进入任何 SQL 列引用。
 *
 * <p>审计时间戳（{@code createdAt / updatedAt}）在重构后为 {@code null}：领域模型按约定不承载审计字段，
 * 适配器只回填真实业务列。字段仍在、形状不变，值不再有——恢复值与收紧字段集都要单独一次变更
 * （与 {@code WalletAccountView} / {@code PointAccountView} 同一处境）。
 */
public record MemberView(
    Long id,
    Long tenantId,
    Long originStoreId,
    Long accountId,
    String imAccount,
    String imUsername,
    LocalDateTime imBoundAt,
    String memberNo,
    String nameCipher,
    String phoneCipher,
    String phoneDigest,
    String name,
    String phone,
    String accountType,
    String imAccountName,
    Boolean imAccountDeleted,
    Long levelId,
    String status,
    LocalDateTime joinedAt,
    LocalDateTime expiresAt,
    Integer version,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

  /** 由"领域档案 + 展示值"装配；领域条目不会被直接序列化出去。 */
  public static MemberView from(MemberDisplay display) {
    if (display == null || display.member() == null) {
      return null;
    }
    Member member = display.member();
    return new MemberView(member.id(), member.tenantId(), member.originStoreId(), member.accountId(),
        member.imAccount(), member.imUsername(), member.imBoundAt(), member.memberNo(),
        member.nameCipher(), member.phoneCipher(), member.phoneDigest(),
        display.name(), display.phone(), display.accountType(), display.imAccountName(),
        display.imAccountDeleted(), member.levelId(), member.status(), member.joinedAt(),
        member.expiresAt(), member.version(), null, null, null, null);
  }
}
