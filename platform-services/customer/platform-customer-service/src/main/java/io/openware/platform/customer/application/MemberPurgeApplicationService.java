package io.openware.platform.customer.application;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.customer.domain.account.model.AccountPurgeCount;
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository;
import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository.ImBinding;
import io.openware.platform.customer.domain.member.repository.MemberNameTokenRepository;
import io.openware.platform.customer.domain.member.repository.MemberPurgeReferenceRepository;
import io.openware.platform.customer.domain.member.repository.MemberPurgeReferenceRepository.PurgeBlockers;
import io.openware.platform.customer.domain.member.repository.MemberRepository;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「无 IM 关联客户」的清理（垃圾数据识别后的可执行动作）。
 *
 * <p>判定口径：{@code cst_member.im_account IS NULL}。回填迁移 V7 保证「账号在统一账号模型里有 IM 登录身份
 * → im_account 有值」，因此该条件等价于「这个客户档案挂的账号<b>根本没有 IM 身份</b>」——
 * 门店口径的垃圾数据（正常客户都是从 A380 入口经 IM 授权进来的）。
 *
 * <p><b>两道闸，任何一道不过就拒绝，绝不静默删</b>：
 * <ol>
 *   <li>有 IM 关联（{@code im_account} 非空）→ 409 {@code MEMBER_HAS_IM_BINDING}；</li>
 *   <li>有业务引用（订单/预约/券，或储值账户，或非零积分）→ 409 {@code MEMBER_HAS_REFERENCES}
 *       —— 删了会留下孤儿引用，这类必须人工判断（例如员工账号上误建的档案但已产生订单）。</li>
 * </ol>
 *
 * <p>删除动作只动<b>本域附属行</b>：姓名盲索引 token、零额积分账户、零额储值账户、客户档案本身；
 * 每个删除都写审计（{@code member.purge_unlinked}），detail 不含姓名/手机号（明文密文都不进审计）。
 *
 * <p><b>跨五个表的删除一律经端口</b>：{@code domain.member.*} / {@code domain.point.*} /
 * {@code domain.wallet.*}。删除条件写在各仓储的窄口清理方法里（"哪些算空账户"这种判定
 * 不该由维护用例在调用点上表达）；本服务只负责<b>顺序与闸门</b>——闸门的顺序是不可换的。
 */
@Slf4j
@Service
public class MemberPurgeApplicationService {

    private final MemberRepository memberRepository;
    private final MemberNameTokenRepository nameTokenRepository;
    private final MemberPurgeReferenceRepository referenceRepository;
    private final MemberImBindingRepository imBindingRepository;
    private final PointAccountRepository pointAccountRepository;
    private final WalletAccountRepository walletAccountRepository;
    private final AuditClient auditClient;

    @Autowired
    public MemberPurgeApplicationService(MemberRepository memberRepository,
                                         MemberNameTokenRepository nameTokenRepository,
                                         MemberPurgeReferenceRepository referenceRepository,
                                         MemberImBindingRepository imBindingRepository,
                                         PointAccountRepository pointAccountRepository,
                                         WalletAccountRepository walletAccountRepository,
                                         AuditClient auditClient) {
        this.memberRepository = memberRepository;
        this.nameTokenRepository = nameTokenRepository;
        this.referenceRepository = referenceRepository;
        this.imBindingRepository = imBindingRepository;
        this.pointAccountRepository = pointAccountRepository;
        this.walletAccountRepository = walletAccountRepository;
        this.auditClient = auditClient;
    }

    /** 清理结果：客户号 + 删掉的行数（姓名索引 / 积分账户）。 */
    public record PurgeResult(String memberNo, int nameTokensDeleted, int pointAccountsDeleted) {}

    /** 清理一个「无 IM 关联且无业务引用」的客户档案。 */
    @Transactional
    public PurgeResult purgeUnlinked(Long id) {
        Member member = id == null ? null : memberRepository.findById(id).orElse(null);
        if (member == null) {
            // 跨租户也走这里：不暴露「存在但不属于你」。
            throw new ApiException(HttpStatusCodes.NOT_FOUND, "MEMBER_NOT_FOUND", "客户不存在");
        }
        // 闸门一：IM 关联。判据分两种：①im_account 为空 → 从未关联 IM，是垃圾数据；
        // ②im_account 有值但 IM 侧用户已不存在（管理员删除后的 IM 用户行缺失）
        //   → 同样失去有效 IM 关联，允许按垃圾数据清理；只有 IM 账号仍然有效时才拒绝。
        boolean imGone = false;
        if (!member.unlinkedFromIm()) {
            imGone = isImAccountGone(member);
            if (!imGone) {
                throw new ApiException(HttpStatusCodes.CONFLICT, "MEMBER_HAS_IM_BINDING",
                        "该客户已关联 IM（" + member.imAccount() + "），不能按垃圾数据清理");
            }
            log.info("客户档案挂的 IM 账号已不存在，按垃圾数据清理: memberId={}, imAccount={}",
                    id, member.imAccount());
        }
        // 闸门二：跨域业务引用体检。读不到体检结果一律按"有引用"拒绝（fail-closed）——
        // 端口实现缺失计数时会抛出，这里把它翻译成同一句可执行的拒绝，绝不因为读不到就放行删除。
        PurgeBlockers blockers;
        try {
            blockers = referenceRepository.blockersFor(id);
        } catch (RuntimeException failure) {
            log.warn("客户引用体检失败，保守拒绝清理: memberId={}, cause={}", id, failure.getMessage());
            throw new ApiException(HttpStatusCodes.CONFLICT, "MEMBER_HAS_REFERENCES",
                    "该客户引用体检失败，删除会留下孤儿引用，请人工核对后再处理");
        }
        String blockerText = describeBlockers(blockers);
        if (blockerText != null) {
            throw new ApiException(HttpStatusCodes.CONFLICT, "MEMBER_HAS_REFERENCES",
                    "该客户已有业务数据（" + blockerText + "），删除会留下孤儿引用，请人工核对后再处理");
        }

        int tokens = nameTokenRepository.deleteByMember(id);
        // 顺序与范围都保持原样：先清零额储值账户（流水→账户），再清积分账户（零额流水→全部账户），最后删档案。
        AccountPurgeCount wallets = walletAccountRepository == null
                ? AccountPurgeCount.none() : walletAccountRepository.purgeEmptyAccountsFor(id);
        AccountPurgeCount points = pointAccountRepository == null
                ? AccountPurgeCount.none() : pointAccountRepository.purgeAccountsFor(id);
        memberRepository.deleteById(id);

        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("member.purge_unlinked")
                .resourceType("cst_member").resourceId(String.valueOf(id))
                .resourceName(member.memberNo())
                .idempotencyKey("member-purge-unlinked:" + id)
                .detailJson("{\"memberNo\":\"" + member.memberNo() + "\",\"imAccountDeleted\":" + imGone
                        + ",\"nameTokensDeleted\":" + tokens
                        + ",\"pointAccountsDeleted\":" + points.accounts()
                        + ",\"pointLedgersDeleted\":" + points.ledgers()
                        + ",\"walletAccountsDeleted\":" + wallets.accounts()
                        + ",\"walletLedgersDeleted\":" + wallets.ledgers() + "}")
                .build());
        log.info("清理无 IM 关联客户: memberId={}, memberNo={}, tokens={}, points={}",
                id, member.memberNo(), tokens, points.accounts());
        return new PurgeResult(member.memberNo(), tokens, points.accounts());
    }

    /**
     * IM 侧账号是否已不存在：以<b>客户档案为驱动表</b>解析它的 IM 登录标识
     * （{@code im_<id>}，标识行缺失时用档案上的 {@code im_account} 兜底）后查不到 IM 用户行，
     * 管理员删除采用硬删除，判断以 IM 用户行是否存在为准。
     *
     * <p>fail-closed：查询本身失败时返回 false（即仍然拒绝清理），
     * 宁可让人工处理，也不误删一个可能仍然有效的 IM 关联。
     */
    private boolean isImAccountGone(Member member) {
        if (member.id() == null) {
            return false;
        }
        try {
            for (ImBinding binding : imBindingRepository.resolveBindings(List.of(member.id()))) {
                if (binding.imAccountGone()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException failure) {
            log.warn("判断 IM 账号是否仍存在失败，保守拒绝清理: memberId={}, cause={}",
                    member.id(), failure.getMessage());
            return false;
        }
    }

    /** 把体检计数拼成一句中文原因；全部为 0 时返回 null（可删）。文案与顺序保持既有实现不变。 */
    private static String describeBlockers(PurgeBlockers blockers) {
        if (blockers == null || blockers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendIfAny(sb, blockers.orders(), "订单");
        appendIfAny(sb, blockers.reservations(), "预约");
        appendIfAny(sb, blockers.wallets(), "储值账户");
        appendIfAny(sb, blockers.points(), "积分余额");
        appendIfAny(sb, blockers.couponsIssued(), "优惠券");
        appendIfAny(sb, blockers.couponsRedeemed(), "券核销记录");
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendIfAny(StringBuilder sb, long count, String label) {
        if (count > 0) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(label).append(' ').append(count).append(" 条");
        }
    }
}
