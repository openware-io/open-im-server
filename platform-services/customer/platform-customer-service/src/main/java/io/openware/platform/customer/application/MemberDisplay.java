package io.openware.platform.customer.application;

import io.openware.platform.customer.domain.member.model.Member;

/**
 * 客户档案 + <b>展示值</b>（应用层载体，不是响应类型）。
 *
 * <p>领域模型 {@link Member} 刻意不承载展示字段：{@code name} / {@code phone} 是密文解密后的回显
 * （无 {@code member.pii.view} 权限时是脱敏值），{@code accountType} / {@code imAccountName} /
 * {@code imAccountDeleted} 来自 {@code idt_*} 与 {@code open_im.*} 的只读补齐。它们不落库、也不参与
 * 任何列引用，因此必须留在领域模型之外（对 {@code exist = false} 的字段生成列引用会在运行时抛
 * {@code can not find lambda cache for this property}）；但它们是<b>既有响应字段</b>，
 * 所以由本载体一路带到 {@code api.dto.MemberView}。
 *
 * <p>响应装配只发生在控制器（{@code MemberView.from(...)}），因此本类型不会被直接序列化出去。
 *
 * @param member           领域档案
 * @param name             解密后的姓名（无权限时为脱敏值）
 * @param phone            解密后的手机号（无权限时为脱敏值）
 * @param accountType      账号类型（{@code idt_account.account_type}）；补齐失败时为 {@code null}
 * @param imAccountName    IM 侧真实账号（{@code open_im.user.username}）；补齐失败时为 {@code null}
 * @param imAccountDeleted IM 账号是否已不存在（以 IM 用户行缺失为准）
 */
public record MemberDisplay(
    Member member,
    String name,
    String phone,
    String accountType,
    String imAccountName,
    Boolean imAccountDeleted) {

  /** 不带任何展示补齐的档案（写路径返回的形态：调用方只关心领域字段）。 */
  public static MemberDisplay withoutDisplay(Member member) {
    return new MemberDisplay(member, null, null, null, null, null);
  }

  /** 补齐展示值（写路径在解密回显后再回填账号类型/IM 账号列）。 */
  public MemberDisplay withDisplay(String newName, String newPhone, String newAccountType,
                                   String newImAccountName, Boolean newImAccountDeleted) {
    return new MemberDisplay(member, newName, newPhone, newAccountType, newImAccountName,
        newImAccountDeleted);
  }
}
