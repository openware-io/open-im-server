package io.openware.platform.customer.domain.member.repository;

import java.util.List;
import java.util.Map;

/**
 * 客户档案的<b>账号与 IM 关联解析</b>端口（{@code idt_*} / {@code open_im.*} 跨域只读查询）。
 *
 * <p>它<b>不是</b> {@code MemberRepository} 的一部分，因为读的根本不是 {@code cst_member} 的行，
 * 而是"这条档案挂的是个什么账号、在 IM 侧还连着谁、那个账号还在不在"。两个消费方，两种用途：
 * 列表页补展示字段（{@code accountType} / {@code imAccountName} / {@code imAccountDeleted}），
 * 清理用例判定"IM 账号是否已不存在"。
 *
 * <p>两条都是<b>批量</b>查询（整页一次，不逐行查库），且都是只读跨域查询：失败只影响展示，
 * 不阻塞客户列表（调用方各自 try/catch）。
 *
 * <p><b>驱动表必须是客户档案</b>：若以 {@code idt_login_identity} 为驱动表，IM 后台把该账号的
 * 登录标识行<b>物理删掉</b>之后，这里会"查不到行 → 不标记已删除"，客户列表就会把已删的 IM 账号
 * 继续显示为有效关联，清理用例也再也清理不掉这份档案（静默回归）。
 * 以档案驱动、并用 {@code im_account} 兜底解析，删标识行后判定依旧成立。
 */
public interface MemberImBindingRepository {

  /**
   * 按账号取 IM 登录标识（{@code idt_login_identity}，仅 ACTIVE 的 IM 身份）。
   *
   * <p>这是"建档时就回填 IM 信息"的取数入口：{@code idt_*} 与 {@code cst_*} 同库且不带 {@code tenant_id}，
   * 因此忽略租户行过滤（与 admin 报表跨域 join 同一范式）；取不到就返回 {@code null}——
   * <b>IM 没有这个信息时不伪造</b>，客户档案照建（员工也可以是消费者）。
   */
  String findImAccountByAccountId(Long accountId);

  /** 按账号取最新一条 IM 昵称快照（{@code idt_profile_sync_record}）；没有就 {@code null}。 */
  String findImNicknameByAccountId(Long accountId);

  /**
   * 批量取账号类型（{@code idt_account.account_type}：CUSTOMER / EMPLOYEE / PLATFORM_OPERATOR）。
   *
   * <p>客户档案与运营人员共用一张账号主体表，页面要能看出"这条客户档案挂的是不是一个员工账号"。
   *
   * @param accountIds 账号 id；为空即不回查
   * @return 账号 id → 类型；查不到的账号不出现在结果里（不伪造）
   */
  Map<Long, String> findAccountTypes(List<Long> accountIds);

  /**
   * 批量解析 IM 关联状态（客户档案驱动，{@code memberIds} 为空即不回查）。
   *
   * <p>IM 管理员删除采用<b>硬删除</b>（不使用 {@code deleted_*} 墓碑用户名），
   * 因此"IM 账号已删除"的口径就是<b>IM 用户行缺失</b>。
   */
  List<ImBinding> resolveBindings(List<Long> memberIds);

  /**
   * 一条档案的 IM 关联状态。
   *
   * @param memberId        客户档案 id
   * @param accountId       档案上的账号 id；可为空
   * @param imUserId        IM 用户 id；{@code null} = IM 用户行缺失
   * @param imUsername      IM 侧真实账号（{@code open_im.user.username}，如 {@code user600}）；可为空
   * @param imNickname      IM 昵称；可为空
   * @param imAccountGone   IM 用户行是否缺失（已删除）。管理员删除是硬删除，故以行缺失为准
   */
  record ImBinding(Long memberId, Long accountId, Long imUserId, String imUsername, String imNickname,
                   boolean imAccountGone) {
  }
}
