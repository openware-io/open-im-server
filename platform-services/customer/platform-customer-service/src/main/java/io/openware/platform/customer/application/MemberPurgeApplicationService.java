package io.openware.platform.customer.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import io.openware.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberNameTokenPo;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 「无 IM 关联客户」的清理（垃圾数据识别后的可执行动作）。
 *
 * <p>判定口径：{@code cst_member.im_account IS NULL}。回填迁移 V7 保证「账号在统一账号模型里有 IM 登录身份
 * → im_account 有值」，因此该条件等价于「这个客户档案挂的账号**根本没有 IM 身份**」——
 * 门店口径的垃圾数据（正常客户都是从 A380 入口经 IM 授权进来的）。
 *
 * <p><b>两道闸，任何一道不过就拒绝，绝不静默删</b>：
 * <ol>
 *   <li>有 IM 关联（{@code im_account} 非空）→ 409 {@code MEMBER_HAS_IM_BINDING}；</li>
 *   <li>有业务引用（订单/预约/券，或储值账户，或非零积分）→ 409 {@code MEMBER_HAS_REFERENCES}
 *       —— 删了会留下孤儿引用，这类必须人工判断（例如员工账号上误建的档案但已产生订单）。</li>
 * </ol>
 *
 * <p>删除动作只动**本域附属行**：姓名盲索引 token、零额积分账户、客户档案本身；每个删除都写审计
 * （{@code member.purge_unlinked}），detail 不含姓名/手机号（明文密文都不进审计）。
 */
@Slf4j
@Service
public class MemberPurgeApplicationService {

    private final MemberMapper memberMapper;
    private final MemberNameTokenMapper nameTokenMapper;
    private final PointAccountMapper pointAccountMapper;
    private final AuditClient auditClient;

    public MemberPurgeApplicationService(MemberMapper memberMapper,
                                         MemberNameTokenMapper nameTokenMapper,
                                         PointAccountMapper pointAccountMapper,
                                         AuditClient auditClient) {
        this.memberMapper = memberMapper;
        this.nameTokenMapper = nameTokenMapper;
        this.pointAccountMapper = pointAccountMapper;
        this.auditClient = auditClient;
    }

    /** 清理结果：客户号 + 删掉的行数（姓名索引 / 零额积分账户）。 */
    public record PurgeResult(String memberNo, int nameTokensDeleted, int pointAccountsDeleted) {}

    /** 清理一个「无 IM 关联且无业务引用」的客户档案。 */
    @Transactional
    public PurgeResult purgeUnlinked(Long id) {
        CstMemberPo member = id == null ? null : memberMapper.selectById(id);
        if (member == null) {
            // 跨租户也走这里：不暴露「存在但不属于你」。
            throw new ApiException(HttpStatusCodes.NOT_FOUND, "MEMBER_NOT_FOUND", "客户不存在");
        }
        // IM 关联判据分两种：①im_account 为空 → 从未关联 IM，是垃圾数据；
        // ②im_account 有值但 IM 侧用户已不存在（行被删或 username 已是 deleted_<id>_<hash> 形态，即后台「删除用户」留下的残档）
        //   → 同样失去有效 IM 关联，允许按垃圾数据清理；只有 IM 账号仍然有效时才拒绝。
        boolean imGone = false;
        if (member.getImAccount() != null && !member.getImAccount().isBlank()) {
            imGone = isImAccountGone(member);
            if (!imGone) {
                throw new ApiException(HttpStatusCodes.CONFLICT, "MEMBER_HAS_IM_BINDING",
                        "该客户已关联 IM（" + member.getImAccount() + "），不能按垃圾数据清理");
            }
            log.info("客户档案挂的 IM 账号已不存在，按垃圾数据清理: memberId={}, imAccount={}",
                    id, member.getImAccount());
        }
        String blockers = describeBlockers(memberMapper.selectPurgeBlockers(id));
        if (blockers != null) {
            throw new ApiException(HttpStatusCodes.CONFLICT, "MEMBER_HAS_REFERENCES",
                    "该客户已有业务数据（" + blockers + "），删除会留下孤儿引用，请人工核对后再处理");
        }

        int tokens = nameTokenMapper.delete(new QueryWrapper<CstMemberNameTokenPo>().eq("member_id", id));
        int points = pointAccountMapper.delete(new QueryWrapper<CstPointAccountPo>().eq("customer_id", id));
        memberMapper.deleteById(id);

        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("member.purge_unlinked")
                .resourceType("cst_member").resourceId(String.valueOf(id))
                .resourceName(member.getMemberNo())
                .idempotencyKey("member-purge-unlinked:" + id)
                .detailJson("{\"memberNo\":\"" + member.getMemberNo() + "\",\"imAccountDeleted\":" + imGone
                        + ",\"nameTokensDeleted\":" + tokens
                        + ",\"pointAccountsDeleted\":" + points + "}")
                .build());
        log.info("清理无 IM 关联客户: memberId={}, memberNo={}, tokens={}, points={}",
                id, member.getMemberNo(), tokens, points);
        return new PurgeResult(member.getMemberNo(), tokens, points);
    }

    /**
     * IM 侧账号是否已不存在：以**客户档案为驱动表**（{@code selectImAccounts} 的 memberIds 口径）解析它的
     * IM 登录标识（{@code im_<id>}，标识行缺失时用档案上的 {@code im_account} 兜底）后查不到 IM 用户行，
     * 或该行 username 已是 {@code deleted_<id>_<hash>}（IM 后台删除用户留下的形态）。
     *
     * <p>fail-closed：查询本身失败时返回 false（即仍然拒绝清理），
     * 宁可让人工处理，也不误删一个可能仍然有效的 IM 关联。
     */
    private boolean isImAccountGone(CstMemberPo member) {
        if (member.getId() == null) {
            return false;
        }
        try {
            for (Map<String, Object> row : memberMapper.selectImAccounts(List.of(member.getId()))) {
                Object deleted = row.get("deleted");
                if (deleted instanceof Number number && number.intValue() == 1) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException failure) {
            log.warn("判断 IM 账号是否仍存在失败，保守拒绝清理: memberId={}, cause={}",
                    member.getId(), failure.getMessage());
            return false;
        }
    }

    /** 把体检计数拼成一句中文原因；全部为 0 时返回 null（可删）。 */
    private static String describeBlockers(Map<String, Object> blockers) {
        if (blockers == null || blockers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendIfAny(sb, blockers, "orders_count", "订单");
        appendIfAny(sb, blockers, "reservations_count", "预约");
        appendIfAny(sb, blockers, "wallets_count", "储值账户");
        appendIfAny(sb, blockers, "points_count", "积分余额");
        appendIfAny(sb, blockers, "coupons_issued_count", "优惠券");
        appendIfAny(sb, blockers, "coupons_redeemed_count", "券核销记录");
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendIfAny(StringBuilder sb, Map<String, Object> blockers, String key, String label) {
        Object value = blockers.get(key);
        long count = value instanceof Number number ? number.longValue() : 0L;
        if (count > 0) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(label).append(' ').append(count).append(" 条");
        }
    }
}
