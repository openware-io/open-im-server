package io.openware.platform.customer.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.crypto.AesGcmCipher;
import io.openware.common.exception.ApiException;
import io.openware.common.util.SnowflakeIdGenerator;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import io.openware.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberNameTokenPo;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户档案应用服务（表名仍为 cst_member，业务口径已按「客户」）。
 *
 * <p>客户号用雪花发号器（全局唯一）；姓名/手机用 AES-256-GCM 加密；手机号摘要用 SHA-256。
 * marketing_consent 授权待接入 mkt_consent。
 *
 * <p><b>检索</b>：姓名是随机 IV 密文，SQL 无法 LIKE，改用**盲索引**
 * （{@link NameBlindIndex} + {@code cst_member_name_token}）：关键词切 1/2/3-gram token，
 * 要求全部命中（AND）→ 包含语义的候选集，再与客户号 LIKE / IM 账号 LIKE / 手机号摘要精确匹配一起 OR。
 *
 * <p><b>去重</b>：同一个 IM 用户/账号不允许再生成第二条客户。三条写路径
 * （{@link #create}、{@link #getOrCreateByAccount}、{@link #bindIm}）都做幂等/冲突判定；
 * 数据库侧 {@code (tenant_id, im_account)} 有唯一索引，{@code (tenant_id, account_id)} 在存在
 * 「有引用的历史重复行」时退化为非唯一索引（见迁移 V6），故应用层判定是必要的一层。
 */
@Slf4j
@Service
public class MemberApplicationService {
    private final MemberMapper memberMapper;
    private final PointAccountMapper pointAccountMapper;
    private final MemberNameTokenMapper nameTokenMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final AesGcmCipher aesGcmCipher;
    private final AuditClient auditClient;

    @Autowired
    public MemberApplicationService(MemberMapper memberMapper, PointAccountMapper pointAccountMapper,
                                    MemberNameTokenMapper nameTokenMapper, SnowflakeIdGenerator idGenerator,
                                    AesGcmCipher aesGcmCipher, AuditClient auditClient) {
        this.memberMapper = memberMapper;
        this.pointAccountMapper = pointAccountMapper;
        this.nameTokenMapper = nameTokenMapper;
        this.idGenerator = idGenerator;
        this.aesGcmCipher = aesGcmCipher;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public MemberApplicationService(MemberMapper memberMapper, PointAccountMapper pointAccountMapper,
                                    MemberNameTokenMapper nameTokenMapper, SnowflakeIdGenerator idGenerator,
                                    AesGcmCipher aesGcmCipher) {
        this(memberMapper, pointAccountMapper, nameTokenMapper, idGenerator, aesGcmCipher, AuditClient.disabled());
    }

    /** 客户隐私信息查看权限码：拥有者回显明文，否则脱敏。 */
    public static final String PII_PERMISSION = "member.pii.view";

    /** 姓名盲索引回填的批大小（按 id 升序游标推进）。 */
    private static final int NAME_INDEX_BATCH_SIZE = 200;

    /** IM 绑定审计动作。 */
    private static final String IM_BIND_ACTION = "member.im.bind";

    /** IM 账号已被另一条客户占用。 */
    private static final String IM_ACCOUNT_ALREADY_BOUND = "IM_ACCOUNT_ALREADY_BOUND";

    /**
     * 客户分页列表。
     *
     * <p>{@code range} 是按**建档时间** {@code cst_member.joined_at} 的闭区间筛选（统一 {@code from}/{@code to}
     * 口径，见 {@link TimeRangeParams}）。两端都可能为空 = 不筛，条件直接拼在 {@code joined_at} 列上
     * （不包函数，走索引）；{@code total} 与 {@code records} 由同一次 {@code selectPage} 得出，口径一致。
     */
    public Page<CstMemberPo> list(long page, long pageSize, String keyword, Long level, String status,
                                  TimeRange range) {
        return list(page, pageSize, keyword, level, status, range, false);
    }

    /** 客户分页列表；onlyUnlinked=true 只取 im_account IS NULL（账号无 IM 身份 = 垃圾数据）。 */
    public Page<CstMemberPo> list(long page, long pageSize, String keyword, Long level, String status,
                                  TimeRange range, boolean onlyUnlinked) {
        LambdaQueryWrapper<CstMemberPo> qw = new LambdaQueryWrapper<>();
        applyKeyword(qw, keyword);
        if (level != null) qw.eq(CstMemberPo::getLevelId, level);
        if (status != null && !status.isBlank()) qw.eq(CstMemberPo::getStatus, status);
        if (onlyUnlinked) qw.isNull(CstMemberPo::getImAccount);
        // 账号类型（idt_account）：客户/员工/平台运营——员工账号上的客户档案要能一眼看出来。
        applyJoinedAtRange(qw, range);
        qw.orderByDesc(CstMemberPo::getId);
        Page<CstMemberPo> result = memberMapper.selectPage(new Page<>(page, pageSize), qw);
        result.getRecords().forEach(this::fillPlain);
        fillAccountTypes(result.getRecords());
        fillImAccounts(result.getRecords());
        return result;
    }

    /**
     * 建档时间闭区间：{@code joined_at >= from AND joined_at <= to}。
     *
     * <p>全仓统一的时间列筛选拼法（与 {@link TimeRangeParams} 同一口径），
     * 只在对应端点为空时不拼条件；列名不加函数包裹，保持索引可用。
     */
    private static void applyJoinedAtRange(LambdaQueryWrapper<CstMemberPo> qw, TimeRange range) {
        if (range == null) {
            return;
        }
        qw.ge(range.hasFrom(), CstMemberPo::getJoinedAt, range.fromInclusive());
        qw.le(range.hasTo(), CstMemberPo::getJoinedAt, range.toInclusive());
    }

    public CstMemberPo get(Long id) {
        CstMemberPo po = memberMapper.selectById(id);
        if (po == null) throw new ApiException(404, "MEMBER_NOT_FOUND", "客户不存在");
        fillPlain(po);
        return po;
    }

    /**
     * 按 accountId 找或建客户（C 端 OAuth 登录后首次访问资产时懒创建）。
     *
     * <p>历史脏数据里同一 accountId 可能有多条（ACK 租户 100 实测 63 行只有 23 个 accountId）：
     * 这里按 id 升序取**最早一条**并打 WARN，不再让 {@code selectOne} 因多行直接抛异常。
     * 迁移 V6 已对「无引用的重复行」做去重，剩下的重复是「有业务引用、不能删」的行。
     */
    @Transactional
    public CstMemberPo getOrCreateByAccount(Long accountId) {
        if (accountId == null) {
            throw new ApiException(400, "ACCOUNT_ID_REQUIRED", "缺少 accountId");
        }
        CstMemberPo existing = findEarliestByAccountId(accountId);
        if (existing != null) {
            fillPlain(existing);
            return existing;
        }
        return create(accountId, "客户", "", false);
    }

    /**
     * 积分管理列表：分页返回客户 + 积分账户余额（积分账户懒加载，缺失按 0）。
     *
     * <p>{@code range} 与客户列表同一口径：按**建档时间** {@code joined_at} 闭区间筛选
     * （积分行是「客户 + 积分账户」，业务时间列仍是客户的建档时间）。
     */
    public Page<MemberPointsRow> pointsList(long page, long pageSize, String keyword, TimeRange range) {
        LambdaQueryWrapper<CstMemberPo> qw = new LambdaQueryWrapper<>();
        applyKeyword(qw, keyword);
        applyJoinedAtRange(qw, range);
        qw.orderByDesc(CstMemberPo::getId);
        Page<CstMemberPo> memberPage = memberMapper.selectPage(new Page<>(page, pageSize), qw);

        List<Long> memberIds = memberPage.getRecords().stream().map(CstMemberPo::getId).toList();
        Map<Long, CstPointAccountPo> pointsMap = new HashMap<>();
        if (!memberIds.isEmpty()) {
            pointAccountMapper.selectList(new LambdaQueryWrapper<CstPointAccountPo>()
                            .in(CstPointAccountPo::getCustomerId, memberIds))
                    .forEach(p -> pointsMap.put(p.getCustomerId(), p));
        }

        List<MemberPointsRow> records = memberPage.getRecords().stream().map(m -> {
            fillPlain(m);
            CstPointAccountPo p = pointsMap.get(m.getId());
            return new MemberPointsRow(m.getId(), m.getMemberNo(), m.getName(), m.getPhone(),
                    p == null ? 0L : (p.getAvailablePoints() == null ? 0L : p.getAvailablePoints()),
                    p == null ? 0L : (p.getFrozenPoints() == null ? 0L : p.getFrozenPoints()));
        }).toList();
        Page<MemberPointsRow> result = new Page<>(page, pageSize, memberPage.getTotal());
        result.setRecords(records);
        return result;
    }

    /** 积分管理列表行（客户 + 积分账户）。 */
    public record MemberPointsRow(Long memberId, String memberNo, String name, String phone,
                                  Long availablePoints, Long frozenPoints) {}

    /** 兼容既有口径：不带 IM 绑定的建档。 */
    @Transactional
    public CstMemberPo create(Long accountId, String name, String phone, Boolean consent) {
        return create(accountId, name, phone, consent, null, null);
    }

    /**
     * 建档（幂等）：{@code accountId} 或 {@code imAccount} 已存在客户 → **返回既有客户**，不新建、不报 500。
     *
     * <p>这是「防止同一 IM 用户生成多条客户」的第一道闸：
     * <ul>
     *   <li>{@code accountId} 非空且已有客户 → 返回最早一条（历史重复数据不炸）；</li>
     *   <li>{@code imAccount} 非空且已被某条客户占用 → 返回那条客户；</li>
     *   <li>两者都为空（后台手工建的「待认领客户」）→ 直接建档，语义与迁移 V6 的
     *       「account_id IS NULL AND im_account IS NULL = 从未关联」一致。</li>
     * </ul>
     * 幂等返回的分支不写 {@code member.create} 审计：本次没有创建任何东西（是读语义的命中）。
     */
    @Transactional
    public CstMemberPo create(Long accountId, String name, String phone, Boolean consent,
                              String imAccount, String imUsername) {
        String normalizedIm = normalizeImAccount(imAccount);

        // 创建时就回填 IM 信息（统一账号模型 idt_login_identity / idt_profile_sync_record）：
        // 账号有 IM 身份才写，IM 没有这个信息就保持空——不伪造；员工账号同口径（员工也可以是消费者）。
        String normalizedImUsername = normalizeImUsername(imUsername);
        if (normalizedIm == null && accountId != null) {
            normalizedIm = normalizeImAccount(memberMapper.selectImAccountByAccountId(accountId));
            if (normalizedIm != null && normalizedImUsername == null) {
                normalizedImUsername = normalizeImUsername(memberMapper.selectImNicknameByAccountId(accountId));
            }
        }
        if (accountId != null) {
            CstMemberPo existing = findEarliestByAccountId(accountId);
            if (existing != null) {
                log.info("建档命中已存在的 accountId，返回既有客户: accountId={}, memberId={}",
                        accountId, existing.getId());
                fillPlain(existing);
                return existing;
            }
        }
        if (normalizedIm != null) {
            CstMemberPo existing = findByImAccount(normalizedIm);
            if (existing != null) {
                log.info("建档命中已存在的 IM 账号，返回既有客户: imAccount={}, memberId={}",
                        normalizedIm, existing.getId());
                fillPlain(existing);
                return existing;
            }
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            CstMemberPo po = new CstMemberPo();
            po.setAccountId(accountId);
            po.setImAccount(normalizedIm);
            po.setImUsername(normalizedImUsername);
            po.setImBoundAt(normalizedIm == null ? null : now);
            // 客户号：雪花发号器全局唯一；name/phone：AES-256-GCM 加密；手机号摘要：SHA-256（检索）。
            po.setMemberNo("M" + idGenerator.nextId());
            po.setNameCipher(aesGcmCipher.encrypt(name));
            po.setPhoneCipher(aesGcmCipher.encrypt(phone));
            po.setPhoneDigest(NameBlindIndex.sha256Hex(phone));
            po.setName(name);
            po.setPhone(phone);
            po.setStatus("ACTIVE");
            po.setJoinedAt(now);
            po.setVersion(0);
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            // consent：真实实现写 mkt_consent（营销授权），脚手架暂不落库
            memberMapper.insert(po);
            // 姓名/客户号盲索引：写入路径必须同步重建，否则新客户按姓名搜不到。
            rebuildNameTokens(po);
            // 建档留痕：detail 只写客户号与是否登记手机号/IM，手机号/姓名密文与明文都不进审计详情。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("member.create")
                    .resourceType("cst_member").resourceId(String.valueOf(po.getId()))
                    .resourceName(po.getMemberNo())
                    .idempotencyKey("member-create:" + po.getId())
                    .detailJson("{\"memberNo\":\"" + po.getMemberNo() + "\",\"hasPhone\":"
                            + (phone != null && !phone.isBlank()) + ",\"hasIm\":"
                            + (normalizedIm != null) + ",\"consent\":" + Boolean.TRUE.equals(consent) + "}")
                    .build());
            return po;
        } catch (RuntimeException failure) {
            // 失败留痕（缺少 accountId/加密失败/唯一键冲突等）：只 WARN，不影响业务异常本身；
            // detail 绝不带姓名/手机号（明文或密文都不进审计）。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("member.create")
                    .resourceType("cst_member")
                    .resourceId(accountId == null ? null : String.valueOf(accountId))
                    .result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure))
                    .detailJson("{\"accountId\":" + accountId + "}")
                    .build());
            throw failure;
        }
    }

    /**
     * 把既有客户绑定到 IM 账号（{@code PUT /business/members/{id}/im-binding}）。
     *
     * <p>{@code imAccount} 已被**另一条**客户占用 → 409 {@code IM_ACCOUNT_ALREADY_BOUND}
     * （消息带现有客户号，便于人工合并）；成功与失败都留痕 {@code member.im.bind}。
     * 传了 {@code name} 时同步更新姓名密文并**重建盲索引**（改姓名必须重建 token）。
     */
    @Transactional
    public CstMemberPo bindIm(Long id, String imAccount, String imUsername, String name) {
        String normalizedIm = normalizeImAccount(imAccount);
        if (normalizedIm == null) {
            throw new ApiException(400, "IM_ACCOUNT_REQUIRED", "缺少 imAccount");
        }
        CstMemberPo member = memberMapper.selectById(id);
        if (member == null) {
            throw new ApiException(404, "MEMBER_NOT_FOUND", "客户不存在");
        }
        CstMemberPo occupied = findByImAccount(normalizedIm);
        if (occupied != null && !occupied.getId().equals(member.getId())) {
            auditImBind(member, normalizedIm, AuditClient.AuditRecord.RESULT_FAILURE, IM_ACCOUNT_ALREADY_BOUND);
            throw new ApiException(409, IM_ACCOUNT_ALREADY_BOUND,
                    "IM 账号已被客户 " + occupied.getMemberNo() + " 绑定");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean rename = name != null && !name.isBlank();
        String normalizedName = normalizeImUsername(imUsername);
        memberMapper.update(null, new LambdaUpdateWrapper<CstMemberPo>()
                .eq(CstMemberPo::getId, member.getId())
                .set(CstMemberPo::getImAccount, normalizedIm)
                .set(CstMemberPo::getImUsername, normalizedName)
                .set(CstMemberPo::getImBoundAt, now)
                .set(CstMemberPo::getUpdatedAt, now)
                .set(rename, CstMemberPo::getNameCipher, rename ? aesGcmCipher.encrypt(name) : null));
        member.setImAccount(normalizedIm);
        member.setImUsername(normalizedName);
        member.setImBoundAt(now);
        if (rename) {
            member.setNameCipher(aesGcmCipher.encrypt(name));
            member.setName(name);
        }
        // 改姓名要重建盲索引；没改姓名也重建（幂等，顺带覆盖客户号 token，成本极低）。
        rebuildNameTokens(member);
        auditImBind(member, normalizedIm, null, null);
        fillPlain(member);
        return member;
    }

    /**
     * 指定租户的姓名盲索引存量回填（可重入）。
     *
     * <p>只处理「**没有 token 行**」的客户（迁移里无法解密姓名，只能在应用层做）：
     * <ol>
     *   <li>跨租户/无上下文扫描用 {@link MemberNameTokenMapper#selectMemberIdsMissingTokens}
     *       （{@code @InterceptorIgnore} + 显式 tenantId），按 id 升序游标分批（每批 200）；</li>
     *   <li>逐条**先设该客户的租户上下文再写**：不设上下文会被租户拦截器整批拒绝；</li>
     *   <li>单条失败只 WARN 并继续（{@code afterId} 照常推进，不会卡在同一行），返回结果里给出 failed 与 remaining。</li>
     * </ol>
     */
    public NameIndexRebuildResult rebuildNameIndexForTenant(long tenantId) {
        int scanned = 0;
        int rebuilt = 0;
        int failed = 0;
        long afterId = 0L;
        while (true) {
            List<Long> batch = nameTokenMapper.selectMemberIdsMissingTokens(tenantId, afterId, NAME_INDEX_BATCH_SIZE);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (Long memberId : batch) {
                if (memberId == null) {
                    continue;
                }
                scanned++;
                // 先推进游标：即使这条客户反复失败，也不会让整批卡在同一行（下一轮重试）。
                afterId = Math.max(afterId, memberId);
                TenantContext previous = TenantContextHolder.get();
                try {
                    TenantContextHolder.set(new TenantContext(tenantId, null, null, 0L, 0));
                    CstMemberPo member = memberMapper.selectById(memberId);
                    if (member == null) {
                        continue;
                    }
                    rebuildNameTokens(member);
                    rebuilt++;
                } catch (RuntimeException failure) {
                    failed++;
                    log.warn("重建客户姓名盲索引失败（跳过该客户，不影响整批；下一轮重试）: tenantId={}, memberId={}, cause={}",
                            tenantId, memberId, failure.getMessage());
                } finally {
                    if (previous == null) {
                        TenantContextHolder.clear();
                    } else {
                        TenantContextHolder.set(previous);
                    }
                }
            }
            if (batch.size() < NAME_INDEX_BATCH_SIZE) {
                break;
            }
        }
        long remaining = nameTokenMapper.countMembersMissingTokens(tenantId);
        return new NameIndexRebuildResult(tenantId, scanned, rebuilt, failed, remaining);
    }

    /** 全租户回填（调度任务入口）：逐租户调用，任一租户失败不影响其它租户（下一轮重试）。 */
    public List<NameIndexRebuildResult> rebuildNameIndexAllTenants() {
        List<Long> tenantIds = nameTokenMapper.selectTenantIdsWithMembers();
        List<NameIndexRebuildResult> results = new ArrayList<>();
        if (tenantIds == null) {
            return results;
        }
        for (Long tenantId : tenantIds) {
            if (tenantId == null) {
                continue;
            }
            try {
                NameIndexRebuildResult result = rebuildNameIndexForTenant(tenantId);
                if (result.rebuilt() > 0 || result.failed() > 0) {
                    log.info("姓名盲索引回填: tenantId={}, scanned={}, rebuilt={}, failed={}, remaining={}",
                            result.tenantId(), result.scanned(), result.rebuilt(), result.failed(), result.remaining());
                }
                results.add(result);
            } catch (RuntimeException failure) {
                log.warn("租户姓名盲索引回填失败（下一轮重试）: tenantId={}, cause={}", tenantId, failure.getMessage());
            }
        }
        return results;
    }

    /**
     * 列表/积分列表关键词检索（各分支 OR，服务端一次 SQL 完成，**总数与过滤口径一致**）：
     * <ol>
     *   <li>客户号包含：{@code member_no LIKE %kw%}（保留，因为回填尚未跑完时 token 表可能还没有存量客户的数据）；</li>
     *   <li>姓名包含：盲索引 token 全命中 → {@code id IN (候选集)}；</li>
     *   <li>手机号（纯数字 7~20 位）：SHA-256 摘要**精确**匹配（保持既有口径）；</li>
     *   <li>IM 账号 / IM 用户名：{@code LIKE %kw%}。</li>
     * </ol>
     *
     * <p><b>关于「token 命中集合为空时直接返回空页」</b>：这里只在**为空时跳过该 OR 分支**，不回空页。
     * 原因是本方法的四个分支是 OR 关系：关键词是手机号或 IM 账号时，盲索引必然没有命中
     * （手机号/IM 根本没进 token 表），若因此直接回空页，②③ 两个明确要求的能力会一起失效。
     * 分支为空时不会拼出 {@code IN ()}（MyBatis-Plus 对空集合会生成非法 SQL），也不会退化成全表扫描
     * —— 剩下的条件仍然是 phone_digest 等值 / member_no、im_* 的 LIKE。空页语义由 SQL 结果自然给出。
     */
    private void applyKeyword(LambdaQueryWrapper<CstMemberPo> qw, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String trimmed = keyword.trim();
        String digest = isPhoneLike(trimmed) ? NameBlindIndex.sha256Hex(trimmed) : null;
        List<Long> tokenHits = nameTokenHits(trimmed);
        qw.and(w -> {
            w.like(CstMemberPo::getMemberNo, trimmed);
            w.or().like(CstMemberPo::getImAccount, trimmed);
            w.or().like(CstMemberPo::getImUsername, trimmed);
            if (digest != null) {
                w.or().eq(CstMemberPo::getPhoneDigest, digest);
            }
            if (!tokenHits.isEmpty()) {
                w.or().in(CstMemberPo::getId, tokenHits);
            }
        });
    }

    /** 关键词的姓名/客户号盲索引候选集；无租户上下文（如同步/离线调用）时不走该通道。 */
    private List<Long> nameTokenHits(String keyword) {
        Set<String> tokens = NameBlindIndex.tokenize(keyword);
        if (tokens.isEmpty()) {
            return List.of();
        }
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null) {
            return List.of();
        }
        List<Long> hits = nameTokenMapper.selectMemberIdsByAllTokens(tenantId, tokens, tokens.size());
        return hits == null ? List.of() : hits;
    }

    private static boolean isPhoneLike(String value) {
        if (value.length() < 7 || value.length() > 20) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 解密回填非持久化明文字段（name/phone）；无「客户隐私查看」权限时脱敏，密文仍保留在 nameCipher/phoneCipher。 */
    private void fillPlain(CstMemberPo po) {
        if (po == null) return;
        boolean canViewPii = canViewPii();
        String name = aesGcmCipher.decrypt(po.getNameCipher());
        String phone = aesGcmCipher.decrypt(po.getPhoneCipher());
        po.setName(canViewPii ? name : maskName(name));
        po.setPhone(canViewPii ? phone : maskPhone(phone));
    }

    private boolean canViewPii() {
        TenantContext ctx = TenantContextHolder.get();
        return ctx != null && ctx.permissions() != null && ctx.permissions().contains(PII_PERMISSION);
    }

    private static String maskName(String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.length() == 1) return name;
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 按 accountId 取最早一条客户；命中多条时打 WARN（历史脏数据不炸）。 */
    private CstMemberPo findEarliestByAccountId(Long accountId) {
        List<CstMemberPo> rows = memberMapper.selectList(new LambdaQueryWrapper<CstMemberPo>()
                .eq(CstMemberPo::getAccountId, accountId)
                .orderByAsc(CstMemberPo::getId)
                .last("LIMIT 2"));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            log.warn("同一 accountId 命中多条客户档案（历史脏数据；迁移 V6 已去重「无引用」的重复行）: "
                    + "accountId={}, 取最早一条 id={}", accountId, rows.get(0).getId());
        }
        return rows.get(0);
    }

    /** 按 IM 账号取客户（同租户内唯一；{@code im_account} 为 NULL 的行不参与）。 */
    private CstMemberPo findByImAccount(String imAccount) {
        List<CstMemberPo> rows = memberMapper.selectList(new LambdaQueryWrapper<CstMemberPo>()
                .eq(CstMemberPo::getImAccount, imAccount)
                .orderByAsc(CstMemberPo::getId)
                .last("LIMIT 2"));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            log.warn("同一 IM 账号命中多条客户档案（应被 uk_cst_member_tenant_im 拦住）: imAccount={}, 取最早一条 id={}",
                    imAccount, rows.get(0).getId());
        }
        return rows.get(0);
    }

    /**
     * 重建一名客户的盲索引 token 集合（**先删后插**，幂等）。
     *
     * <p>token 覆盖「姓名（解密后）+ 客户号」两段文本；名字取自密文解密而不是入参，保证
     * create / im-binding / 回填三条路径用的是同一份口径。
     *
     * <p>失败补偿：删完旧的、插新的过程中失败时，再删一次，把这名客户退回「零 token 行」状态。
     * 否则会留下半套索引，而回填扫描的判据是「没有 token 行」——半套索引会被误判为已完成。
     */
    private void rebuildNameTokens(CstMemberPo member) {
        if (member == null || member.getId() == null) {
            return;
        }
        Long tenantId = member.getTenantId() != null ? member.getTenantId() : TenantContextHolder.tenantIdOrNull();
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(NameBlindIndex.tokenize(aesGcmCipher.decrypt(member.getNameCipher())));
        tokens.addAll(NameBlindIndex.tokenize(member.getMemberNo()));
        nameTokenMapper.delete(tokenOwnerWrapper(member.getId()));
        try {
            LocalDateTime now = LocalDateTime.now();
            for (String token : tokens) {
                CstMemberNameTokenPo row = new CstMemberNameTokenPo();
                row.setTenantId(tenantId);
                row.setMemberId(member.getId());
                row.setToken(token);
                row.setCreatedAt(now);
                nameTokenMapper.insert(row);
            }
        } catch (RuntimeException failure) {
            try {
                nameTokenMapper.delete(tokenOwnerWrapper(member.getId()));
            } catch (RuntimeException compensationFailure) {
                log.warn("补偿删除半套盲索引失败（下一轮回填会再次处理该客户）: memberId={}, cause={}",
                        member.getId(), compensationFailure.getMessage());
            }
            throw failure;
        }
    }

    private static LambdaQueryWrapper<CstMemberNameTokenPo> tokenOwnerWrapper(Long memberId) {
        return new LambdaQueryWrapper<CstMemberNameTokenPo>().eq(CstMemberNameTokenPo::getMemberId, memberId);
    }

    /** 绑定留痕：成功与失败都要有；detail 只写客户号/客户 id 与 IM 账号，绝不带姓名/手机号。 */
    private void auditImBind(CstMemberPo member, String imAccount, String result, String errorCode) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action(IM_BIND_ACTION)
                .resourceType("cst_member").resourceId(String.valueOf(member.getId()))
                .resourceName(member.getMemberNo())
                .result(result)
                .errorCode(errorCode)
                .idempotencyKey("member-im-bind:" + member.getId() + ":" + imAccount
                        + (errorCode == null ? "" : ":fail"))
                .detailJson("{\"memberNo\":\"" + member.getMemberNo() + "\",\"imAccount\":\"" + imAccount + "\"}")
                .build());
    }

    private static String normalizeImAccount(String imAccount) {
        if (imAccount == null) {
            return null;
        }
        String trimmed = imAccount.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeImUsername(String imUsername) {
        if (imUsername == null) {
            return null;
        }
        String trimmed = imUsername.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 批量补齐账号类型（同页一次查询）：取不到就不填（平台库不可达时不阻塞列表）。
     */
    private void fillAccountTypes(java.util.List<CstMemberPo> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        java.util.List<Long> accountIds = new java.util.ArrayList<>();
        for (CstMemberPo record : records) {
            if (record.getAccountId() != null) {
                accountIds.add(record.getAccountId());
            }
        }
        if (accountIds.isEmpty()) {
            return;
        }
        try {
            for (java.util.Map<String, Object> row : memberMapper.selectAccountTypes(accountIds)) {
                Object id = row.get("accountId");
                Object type = row.get("accountType");
                if (id == null || type == null) {
                    continue;
                }
                Long accountId = ((Number) id).longValue();
                for (CstMemberPo record : records) {
                    if (accountId.equals(record.getAccountId())) {
                        record.setAccountType(String.valueOf(type));
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.warn("补齐账号类型失败（不影响客户列表）: cause={}", failure.getMessage());
        }
    }

    /**
     * 批量补齐「IM 侧真实账号」（同页一次查询）：界面「IM 账号」列显示 {@code imAccountName}；
     * IM 用户已删除（无行或 {@code deleted_*}）时置 {@code imAccountDeleted=true}，供页面标红并纳入清理判据。
     */
    private void fillImAccounts(java.util.List<CstMemberPo> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        java.util.List<Long> memberIds = new java.util.ArrayList<>();
        for (CstMemberPo record : records) {
            if (record.getId() != null) {
                memberIds.add(record.getId());
            }
        }
        if (memberIds.isEmpty()) {
            return;
        }
        try {
            // 以客户档案为驱动表：档案没有 account_id（历史数据）也能按 im_account 解析出 IM 账号，
            // 且 IM 后台删掉 idt_login_identity 行后判定不会退化成「未删除」。
            for (java.util.Map<String, Object> row : memberMapper.selectImAccounts(memberIds)) {
                Object memberIdValue = row.get("memberId");
                if (memberIdValue == null) {
                    continue;
                }
                long memberId = ((Number) memberIdValue).longValue();
                for (CstMemberPo record : records) {
                    if (record.getId() != null && record.getId() == memberId) {
                        Object name = row.get("imUsername");
                        Object deleted = row.get("deleted");
                        record.setImAccountName(name == null ? null : String.valueOf(name));
                        record.setImAccountDeleted(deleted instanceof Number number && number.intValue() == 1);
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.warn("补齐 IM 真实账号失败（不影响客户列表）: cause={}", failure.getMessage());
        }
    }
}