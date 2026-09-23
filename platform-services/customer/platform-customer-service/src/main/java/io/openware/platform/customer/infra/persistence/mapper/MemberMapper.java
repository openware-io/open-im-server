package io.openware.platform.customer.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberMapper extends BaseMapper<CstMemberPo> {

    /**
     * 按账号取 IM 登录标识（统一账号模型：{@code idt_login_identity}，仅 ACTIVE 的 IM 身份）。
     *
     * <p>「创建时就回填 IM 信息」的取数入口：{@code idt_*}} 在同一个 open_im 库、且不带 tenant_id，
     * 因此忽略租户行过滤（与 admin 报表跨域 join ord_ / pay_ 同一范式）；取不到就返回 null——
     * **IM 没有这个信息时不伪造**，客户档案照建（员工也可以是消费者）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT li.login_identifier FROM idt_login_identity li "
            + "WHERE li.account_id = #{accountId} AND li.login_type = 'IM' AND li.status = 'ACTIVE' "
            + "ORDER BY li.id LIMIT 1")
    String selectImAccountByAccountId(@Param("accountId") Long accountId);

    /** 按账号取最新一条 IM 昵称快照（{@code idt_profile_sync_record.profile_json.nickname}）；没有就 null。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT JSON_UNQUOTE(JSON_EXTRACT(p.profile_json, '$.nickname')) "
            + "FROM idt_profile_sync_record p WHERE p.account_id = #{accountId} "
            + "ORDER BY p.id DESC LIMIT 1")
    String selectImNicknameByAccountId(@Param("accountId") Long accountId);

    /**
     * 批量取账号类型（{@code idt_account.account_type}）：客户列表按页一次性补齐，不逐行查库。
     * {@code idt_*} 与 {@code cst_*} 同库且不带 tenant_id，故忽略租户行过滤（与 admin 报表跨域 join 同范式）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>SELECT id AS accountId, account_type AS accountType FROM idt_account WHERE id IN "
            + "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    java.util.List<java.util.Map<String, Object>> selectAccountTypes(@Param("accountIds") java.util.List<Long> accountIds);

    /**
     * 清理「无 IM 关联客户」前的**引用体检**（一次查完，fail-closed）：
     * 有订单/预约/券，或有储值账户，或有非零积分，就不允许删除——删了会留下孤儿引用。
     *
     * <p>{@code ord_*}/{@code mkt_*} 与 {@code cst_*} 同库（admin 报表也这样跨域 join），
     * 这里只做只读计数，且忽略租户行过滤（目标行已按租户校验过）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT "
            + "(SELECT COUNT(*) FROM ord_order o WHERE o.customer_id = #{id}) AS orders_count, "
            + "(SELECT COUNT(*) FROM ord_reservation r WHERE r.customer_id = #{id}) AS reservations_count, "
            + "(SELECT COUNT(*) FROM cst_wallet_account w WHERE w.customer_id = #{id}) AS wallets_count, "
            + "(SELECT COUNT(*) FROM cst_point_account p WHERE p.customer_id = #{id} "
            + "   AND (p.available_points <> 0 OR p.frozen_points <> 0)) AS points_count, "
            + "(SELECT COUNT(*) FROM mkt_coupon_issuance ci WHERE ci.customer_id = #{id}) AS coupons_issued_count, "
            + "(SELECT COUNT(*) FROM mkt_coupon_redemption cr WHERE cr.customer_id = #{id}) AS coupons_redeemed_count")
    java.util.Map<String, Object> selectPurgeBlockers(@Param("id") Long id);

    /**
     * 批量取「IM 侧真实账号」：以**客户档案为驱动表**，从统一账号模型的 IM 登录标识（形如 {@code im_<id>}）
     * 或档案上的 {@code im_account} 解析出 IM 用户 id，再取 {@code im_server.user.username / nickname}。
     * 客户管理「IM 账号」列显示的就是它。
     *
     * <p>「IM 账号已删除」（{@code deleted=1}）的口径：IM 用户行缺失，或 username 已是
     * {@code deleted_<id>_<hash>}（IM 后台删除用户留下的墓碑）。这类客户档案在业务上同样是垃圾数据。
     *
     * <p><b>驱动表必须是 cst_member</b>：若以 {@code idt_login_identity} 为驱动表，IM 后台把该账号的登录标识行
     * **物理删掉**之后，这里会「查不到行 → 不标记已删除」，客户列表就会把已删的 IM 账号继续显示为有效关联，
     * {@code MemberPurgeApplicationService#isImAccountGone} 也再也清理不掉这份档案（静默回归）。
     * 以客户档案驱动、并用 {@code im_account} 兜底解析，删标识行后判定依旧成立。
     *
     * <p>只读跨库查询（两库同实例，与 admin 报表跨域 join 同范式），失败不影响客户列表。
     */
    @Select("<script>SELECT m.id AS memberId, m.account_id AS accountId, u.id AS imUserId, "
            + "u.username AS imUsername, u.nickname AS imNickname, "
            + "CASE WHEN u.id IS NULL OR u.username LIKE 'deleted\\_%' THEN 1 ELSE 0 END AS deleted "
            + "FROM cst_member m "
            + "LEFT JOIN idt_login_identity li ON li.account_id = m.account_id "
            + "AND li.login_type = 'IM' AND li.status = 'ACTIVE' "
            + "LEFT JOIN im_server.user u ON u.id = COALESCE("
            + "CASE WHEN li.login_identifier REGEXP '^im_[0-9]+$' "
            + "THEN CAST(SUBSTRING(li.login_identifier, 4) AS UNSIGNED) END, "
            + "CASE WHEN m.im_account REGEXP '^im_[0-9]+$' "
            + "THEN CAST(SUBSTRING(m.im_account, 4) AS UNSIGNED) END) "
            + "WHERE m.id IN <foreach collection='memberIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    @InterceptorIgnore(tenantLine = "true")
    java.util.List<java.util.Map<String, Object>> selectImAccounts(@Param("memberIds") java.util.List<Long> memberIds);
}