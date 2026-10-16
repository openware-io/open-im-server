package io.openware.platform.customer.domain.member.model;

import java.time.LocalDateTime;

/**
 * 客户档案（领域模型）。
 *
 * <p>口径：这张表叫「会员」但实际存的是<b>客户</b>——等级（levelId）/权益/成长值业务都还没有实现，
 * 界面与菜单统一按「客户」展示；字段名与接口路径保持 member 不动，避免授权与路由漂移。
 *
 * <p><b>字段集只来自真实 DDL 列</b>（{@code cst_member}）。{@code CstMemberPo} 上有五个
 * {@code @TableField(exist = false)} 的展示字段（{@code name} / {@code phone} / {@code accountType} /
 * {@code imAccountName} / {@code imAccountDeleted}）——它们<b>不是列</b>，一律不在此承载：
 * 姓名与手机号是随机 IV 密文解密后的回显（无 {@code member.pii.view} 权限时是脱敏值），
 * 账号类型与 IM 账号名来自 {@code idt_*} / {@code open_im.*}。这些展示值由应用层装配成
 * {@code MemberDisplay}，对不存在的列生成列引用会在运行时抛
 * {@code can not find lambda cache for this property}，而 {@code test-compile} 完全看不出来。
 *
 * <p>审计字段（createdBy / createdAt / updatedBy / updatedAt）不在此承载：由适配器填充
 * （与 {@code WalletAccount} / {@code PointAccount} 同一约定）。
 *
 * <p>{@code version} 是 DDL 上的真实列，但本表<b>没有</b>乐观锁语义：PO 上没有 {@code @Version}，
 * 工程里也没有 {@code OptimisticLockerInnerInterceptor}，写路径并不按版本判定。它出现在这里只是为了
 * 让响应视图 {@code MemberView} 能原样保住 {@code version} 这个既有响应字段（契约不变）。
 *
 * @param id            档案标识；{@code null} 表示尚未落库
 * @param tenantId      租户
 * @param originStoreId 首次新增客户的门店；客户主档仍在租户内跨店共享，{@code null} = 总部或历史不可判定
 * @param accountId     SaaS 账号 {@code idt_account.id}；{@code null} = 待认领客户
 * @param imAccount     IM 登录标识（如 {@code im_71} / openId）；{@code null} = 未绑定 IM
 * @param imUsername    IM 用户名/昵称快照
 * @param imBoundAt     IM 绑定时间
 * @param memberNo      客户号（雪花发号器生成，租户内唯一）
 * @param nameCipher    姓名密文（AES-256-GCM，随机 IV）
 * @param phoneCipher   手机号密文（AES-256-GCM，随机 IV）
 * @param phoneDigest   手机号 SHA-256 摘要（精确检索列）
 * @param levelId       会员等级
 * @param status        PENDING / ACTIVE / SUSPENDED / CLOSED
 * @param joinedAt      建档时间
 * @param expiresAt     有效期至
 * @param version       DDL 上的版本列（本表不做乐观锁判定）
 */
public record Member(
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
    Long levelId,
    String status,
    LocalDateTime joinedAt,
    LocalDateTime expiresAt,
    Integer version) {

  /** 生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 是否已落库（{@code id == null} 即尚未落库）。 */
  public boolean persisted() {
    return id != null;
  }

  /**
   * 是否<b>从未关联 IM</b>（{@code im_account} 为空）。
   *
   * <p>这是「垃圾档案」的判据，与 {@code MemberPurgeApplicationService} 的第一道闸同一口径；
   * 「关联已失效」（{@code im_account} 有值但 IM 侧用户行缺失）不属于本判据——那是另一条路径。
   */
  public boolean unlinkedFromIm() {
    return imAccount == null || imAccount.isBlank();
  }

  /** 绑定 IM 后的新实例（不改自身）。{@code nameCipher} 传 {@code null} 表示本次不改姓名。 */
  public Member withImBinding(String newImAccount, String newImUsername, LocalDateTime newImBoundAt,
                              String newNameCipher) {
    return new Member(id, tenantId, originStoreId, accountId, newImAccount, newImUsername, newImBoundAt,
        memberNo, newNameCipher == null ? nameCipher : newNameCipher, phoneCipher, phoneDigest,
        levelId, status, joinedAt, expiresAt, version);
  }
}
