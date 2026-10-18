package io.openware.platform.customer.application;

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
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.member.model.MemberFilter;
import io.openware.platform.customer.domain.member.model.MemberNameToken;
import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository;
import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository.ImBinding;
import io.openware.platform.customer.domain.member.repository.MemberNameTokenRepository;
import io.openware.platform.customer.domain.member.repository.MemberRepository;
import io.openware.platform.customer.domain.member.repository.MemberRepository.MemberIdPage;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户档案应用服务（表名仍为 cst_member，业务口径已按「客户」）。
 *
 * <p>客户号用雪花发号器（全局唯一）；姓名/手机用 AES-256-GCM 加密；手机号摘要用 SHA-256。
 * marketing_consent 授权待接入 mkt_consent。
 *
 * <p><b>检索</b>：姓名是随机 IV 密文，SQL 无法 LIKE，改用<b>盲索引</b>
 * （{@link NameBlindIndex} + {@code cst_member_name_token}）：关键词切 1/2/3-gram token，
 * 要求全部命中（AND）→ 包含语义的候选集，再与客户号 LIKE / IM 账号 LIKE / 手机号摘要精确匹配一起 OR。
 * token 与手机号摘要在本层算好（它们属于应用层的加密口径），端口只消费结果。
 *
 * <p><b>去重</b>：同一个 IM 用户/账号不允许再生成第二条客户。三条写路径
 * （{@link #create}、{@link #getOrCreateByAccount}、{@link #bindIm}）都做幂等/冲突判定；
 * 数据库侧 {@code (tenant_id, im_account)} 有唯一索引，{@code (tenant_id, account_id)} 在存在
 * 「有引用的历史重复行」时退化为非唯一索引（见迁移 V6），故应用层判定是必要的一层。
 *
 * <p><b>分层</b>：本服务只依赖领域端口（{@code domain.member.repository.*} / {@code domain.point.repository.*}），
 * 不再触碰任何 Mapper 或持久化对象；展示字段（明文姓名/手机号、账号类型、IM 侧真实账号）
 * 由 {@link MemberDisplay} 承载，响应装配留给控制器。
 */
@Slf4j
@Service
public class MemberApplicationService {
    private final MemberRepository memberRepository;
    private final MemberNameTokenRepository nameTokenRepository;
    private final MemberImBindingRepository imBindingRepository;
    private final PointAccountRepository pointAccountRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final AesGcmCipher aesGcmCipher;
    private final AuditClient auditClient;

    @Autowired
    public MemberApplicationService(MemberRepository memberRepository,
                                    MemberNameTokenRepository nameTokenRepository,
                                    MemberImBindingRepository imBindingRepository,
                                    PointAccountRepository pointAccountRepository,
                                    SnowflakeIdGenerator idGenerator, AesGcmCipher aesGcmCipher,
                                    AuditClient auditClient) {
        this.memberRepository = memberRepository;
        this.nameTokenRepository = nameTokenRepository;
        this.imBindingRepository = imBindingRepository;
        this.pointAccountRepository = pointAccountRepository;
        this.idGenerator = idGenerator;
        this.aesGcmCipher = aesGcmCipher;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public MemberApplicationService(MemberRepository memberRepository,
                                    MemberNameTokenRepository nameTokenRepository,
                                    MemberImBindingRepository imBindingRepository,
                                    PointAccountRepository pointAccountRepository,
                                    SnowflakeIdGenerator idGenerator, AesGcmCipher aesGcmCipher) {
        this(memberRepository, nameTokenRepository, imBindingRepository, pointAccountRepository,
                idGenerator, aesGcmCipher, AuditClient.disabled());
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
     * <p>{@code range} 是按<b>建档时间</b> {@code joined_at} 的闭区间筛选（统一 {@code from}/{@code to}
     * 口径，见 {@link TimeRangeParams}）。两端都可能为空 = 不筛；{@code total} 与 {@code records}
     * 由同一次查询得出，口径一致。
     */
    public Page<MemberDisplay> list(long page, long pageSize, String keyword, Long level, String status,
                                    TimeRange range) {
        return list(page, pageSize, keyword, level, status, range, false, null);
    }

    /** 客户分页列表；onlyUnlinked=true 只取 im_account IS NULL（账号无 IM 身份 = 垃圾数据）。 */
    public Page<MemberDisplay> list(long page, long pageSize, String keyword, Long level, String status,
                                    TimeRange range, boolean onlyUnlinked) {
        return list(page, pageSize, keyword, level, status, range, onlyUnlinked, null);
    }

    /** 客户列表可按首次新增门店筛选；不传时保持租户内跨店共享主档口径。 */
    public Page<MemberDisplay> list(long page, long pageSize, String keyword, Long level, String status,
                                    TimeRange range, boolean onlyUnlinked, Long originStoreId) {
        long safePage = page < 1 ? 1 : page;
        long safeSize = pageSize < 1 ? 1 : pageSize;
        MemberIdPage idPage = memberRepository.listIds(filter(keyword, level, status, onlyUnlinked,
                originStoreId, range, (safePage - 1) * safeSize, safeSize));
        List<MemberDisplay> records = describeAll(memberRepository.findAllById(idPage.ids()));
        Page<MemberDisplay> result = new Page<>(safePage, safeSize, idPage.total());
        result.setRecords(records);
        return result;
    }

    public MemberDisplay get(Long id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "客户不存在"));
        return describeAll(List.of(member)).get(0);
    }

    /**
     * 按 accountId 找或建客户（C 端 OAuth 登录后首次访问资产时懒创建）。
     *
     * <p>历史脏数据里同一 accountId 可能有多条（ACK 租户 100 实测 63 行只有 23 个 accountId）：
     * 端口按 id 升序取<b>最早一条</b>并打 WARN，不再让查询因多行直接抛异常。
     * 迁移 V6 已对「无引用的重复行」做去重，剩下的重复是「有业务引用、不能删」的行。
     */
    @Transactional
    public MemberDisplay getOrCreateByAccount(Long accountId) {
        if (accountId == null) {
            throw new ApiException(400, "ACCOUNT_ID_REQUIRED", "缺少 accountId");
        }
        Member existing = memberRepository.findEarliestByAccountId(accountId).orElse(null);
        if (existing != null) {
            return describeAll(List.of(existing)).get(0);
        }
        return create(accountId, "客户", "", false, null, null);
    }

    /**
     * 按账号找或建客户；首次懒创建时显式记录来源门店。
     * 读到既有客户不改写其首次建档门店，避免跨店访问污染来源归因。
     */
    @Transactional
    public MemberDisplay getOrCreateByAccount(Long originStoreId, Long accountId) {
        if (accountId == null) {
            throw new ApiException(400, "ACCOUNT_ID_REQUIRED", "缺少 accountId");
        }
        Member existing = memberRepository.findEarliestByAccountId(accountId).orElse(null);
        if (existing != null) {
            return describeAll(List.of(existing)).get(0);
        }
        if (originStoreId == null) {
            throw new ApiException(400, "STORE_CONTEXT_REQUIRED", "首次创建客户必须在门店上下文中发起");
        }
        try {
            return create(originStoreId, accountId, "客户", "", false, null, null);
        } catch (DuplicateKeyException race) {
            // 两个首屏资产请求可能并发懒创建同一客户；唯一键竞争的一方重新读取赢家，保持接口幂等。
            Member winner = memberRepository.findEarliestByAccountId(accountId).orElse(null);
            if (winner == null) {
                String imAccount = normalizeImAccount(bindingForAccount(accountId));
                if (imAccount != null) {
                    winner = memberRepository.findEarliestByImAccount(imAccount).orElse(null);
                }
            }
            if (winner != null) {
                log.debug("并发建档命中账号或 IM 唯一键，返回已创建客户: accountId={}, memberId={}",
                        accountId, winner.id());
                return describeAll(List.of(winner)).get(0);
            }
            throw race;
        }
    }

    /**
     * 积分管理列表：分页返回客户 + 积分账户余额（积分账户懒加载，缺失按 0）。
     *
     * <p>{@code range} 与客户列表同一口径：按<b>建档时间</b> {@code joined_at} 闭区间筛选
     * （积分行是「客户 + 积分账户」，业务时间列仍是客户的建档时间）。
     */
    public Page<MemberPointsRow> pointsList(long page, long pageSize, String keyword, TimeRange range) {
        return pointsList(page, pageSize, keyword, range, null);
    }

    /** 积分管理列表可按客户首次新增门店筛选；积分余额仍是租户级共享。 */
    public Page<MemberPointsRow> pointsList(long page, long pageSize, String keyword, TimeRange range,
                                            Long originStoreId) {
        long safePage = page < 1 ? 1 : page;
        long safeSize = pageSize < 1 ? 1 : pageSize;
        MemberIdPage idPage = memberRepository.listIds(filter(keyword, null, null, false, originStoreId,
                range, (safePage - 1) * safeSize, safeSize));
        List<Member> members = memberRepository.findAllById(idPage.ids());

        // 整页一次批量查询（不逐会员查积分）：缺失 = 余额 0。
        Map<Long, PointAccount> pointsByCustomer = pointAccountRepository.findByCustomers(idPage.ids());

        List<MemberPointsRow> records = members.stream().map(member -> {
            MemberDisplay display = describePlain(member);
            PointAccount account = pointsByCustomer.get(member.id());
            return new MemberPointsRow(member.id(), member.memberNo(), display.name(), display.phone(),
                    account == null ? 0L : account.available(),
                    account == null ? 0L : account.frozen());
        }).toList();
        Page<MemberPointsRow> result = new Page<>(safePage, safeSize, idPage.total());
        result.setRecords(records);
        return result;
    }

    /** 积分管理列表行（客户 + 积分账户）。 */
    public record MemberPointsRow(Long memberId, String memberNo, String name, String phone,
                                  Long availablePoints, Long frozenPoints) {}

    /** 兼容既有口径：不带 IM 绑定的建档。 */
    @Transactional
    public MemberDisplay create(Long accountId, String name, String phone, Boolean consent) {
        return create(accountId, name, phone, consent, null, null);
    }

    /**
     * 建档（幂等）：{@code accountId} 或 {@code imAccount} 已存在客户 → <b>返回既有客户</b>，不新建、不报 500。
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
    public MemberDisplay create(Long accountId, String name, String phone, Boolean consent,
                                String imAccount, String imUsername) {
        return create(null, accountId, name, phone, consent, imAccount, imUsername);
    }

    /** 入站适配器显式传入已验签门店；不从请求体推断归因。 */
    @Transactional
    public MemberDisplay create(Long originStoreId, Long accountId, String name, String phone, Boolean consent,
                                String imAccount, String imUsername) {
        String normalizedIm = normalizeImAccount(imAccount);

        // 创建时就回填 IM 信息（统一账号模型 idt_login_identity / idt_profile_sync_record）：
        // 账号有 IM 身份才写，IM 没有这个信息就保持空——不伪造；员工账号同口径（员工也可以是消费者）。
        String normalizedImUsername = normalizeImUsername(imUsername);
        if (normalizedIm == null && accountId != null) {
            normalizedIm = normalizeImAccount(bindingForAccount(accountId));
            if (normalizedIm != null && normalizedImUsername == null) {
                normalizedImUsername = normalizeImUsername(nicknameForAccount(accountId));
            }
        }
        if (accountId != null) {
            Member existing = memberRepository.findEarliestByAccountId(accountId).orElse(null);
            if (existing != null) {
                log.info("建档命中已存在的 accountId，返回既有客户: accountId={}, memberId={}",
                        accountId, existing.id());
                return describeAll(List.of(existing)).get(0);
            }
        }
        if (normalizedIm != null) {
            Member existing = memberRepository.findEarliestByImAccount(normalizedIm).orElse(null);
            if (existing != null) {
                log.info("建档命中已存在的 IM 账号，返回既有客户: imAccount={}, memberId={}",
                        normalizedIm, existing.id());
                return describeAll(List.of(existing)).get(0);
            }
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            // 客户号：雪花发号器全局唯一；name/phone：AES-256-GCM 加密；手机号摘要：SHA-256（检索）。
            // consent：真实实现写 mkt_consent（营销授权），脚手架暂不落库。
            Member created = memberRepository.insert(new Member(null,
                    TenantContextHolder.tenantIdOrNull(), originStoreId, accountId, normalizedIm,
                    normalizedImUsername, normalizedIm == null ? null : now,
                    "M" + idGenerator.nextId(), aesGcmCipher.encrypt(name), aesGcmCipher.encrypt(phone),
                    NameBlindIndex.sha256Hex(phone), null, Member.STATUS_ACTIVE, now, null, 0));
            // 姓名/客户号盲索引：写入路径必须同步重建，否则新客户按姓名搜不到。
            rebuildNameTokens(created);
            // 建档留痕：detail 只写客户号与是否登记手机号/IM，手机号/姓名密文与明文都不进审计详情。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("member.create")
                    .resourceType("cst_member").resourceId(String.valueOf(created.id()))
                    .resourceName(created.memberNo())
                    .idempotencyKey("member-create:" + created.id())
                    .detailJson("{\"memberNo\":\"" + created.memberNo() + "\",\"hasPhone\":"
                            + (phone != null && !phone.isBlank()) + ",\"hasIm\":"
                            + (normalizedIm != null) + ",\"consent\":" + Boolean.TRUE.equals(consent) + "}")
                    .build());
            return describeAll(List.of(created)).get(0);
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
     * <p>{@code imAccount} 已被<b>另一条</b>客户占用 → 409 {@code IM_ACCOUNT_ALREADY_BOUND}
     * （消息带现有客户号，便于人工合并）；成功与失败都留痕 {@code member.im.bind}。
     * 传了 {@code name} 时同步更新姓名密文并<b>重建盲索引</b>（改姓名必须重建 token）。
     */
    @Transactional
    public MemberDisplay bindIm(Long id, String imAccount, String imUsername, String name) {
        String normalizedIm = normalizeImAccount(imAccount);
        if (normalizedIm == null) {
            throw new ApiException(400, "IM_ACCOUNT_REQUIRED", "缺少 imAccount");
        }
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "客户不存在"));
        Member occupied = memberRepository.findEarliestByImAccount(normalizedIm).orElse(null);
        if (occupied != null && !occupied.id().equals(member.id())) {
            auditImBind(member, normalizedIm, AuditClient.AuditRecord.RESULT_FAILURE,
                    IM_ACCOUNT_ALREADY_BOUND);
            throw new ApiException(409, IM_ACCOUNT_ALREADY_BOUND,
                    "IM 账号已被客户 " + occupied.memberNo() + " 绑定");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean rename = name != null && !name.isBlank();
        String normalizedName = normalizeImUsername(imUsername);
        String newNameCipher = rename ? aesGcmCipher.encrypt(name) : null;
        if (!memberRepository.updateImBinding(member.id(), normalizedIm, normalizedName, now, newNameCipher,
                member.version())) {
          throw new ApiException(409, "MEMBER_VERSION_CONFLICT", "客户档案已被其他请求修改，请刷新后重试");
        }
        Member bound = member.withImBinding(normalizedIm, normalizedName, now, newNameCipher);
        // 改姓名要重建盲索引；没改姓名也重建（幂等，顺带覆盖客户号 token，成本极低）。
        rebuildNameTokens(bound);
        auditImBind(bound, normalizedIm, null, null);
        return describeAll(List.of(bound)).get(0);
    }

    /**
     * 指定租户的姓名盲索引存量回填（可重入）。
     *
     * <p>只处理「<b>没有 token 行</b>」的客户（迁移里无法解密姓名，只能在应用层做）：
     * <ol>
     *   <li>跨租户/无上下文扫描用
     *       {@link MemberNameTokenRepository#findMemberIdsMissingTokens}（适配器忽略租户拦截器 +
     *       显式 tenantId），按 id 升序游标分批（每批 200）；</li>
     *   <li>逐条<b>先设该客户的租户上下文再写</b>：不设上下文会被租户拦截器整批拒绝；</li>
     *   <li>单条失败只 WARN 并继续（{@code afterId} 照常推进，不会卡在同一行），返回结果里给出 failed 与 remaining。</li>
     * </ol>
     */
    public NameIndexRebuildResult rebuildNameIndexForTenant(long tenantId) {
        int scanned = 0;
        int rebuilt = 0;
        int failed = 0;
        long afterId = 0L;
        while (true) {
            List<Long> batch = nameTokenRepository.findMemberIdsMissingTokens(tenantId, afterId,
                    NAME_INDEX_BATCH_SIZE);
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
                    Member member = memberRepository.findById(memberId).orElse(null);
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
        long remaining = nameTokenRepository.countMembersMissingTokens(tenantId);
        return new NameIndexRebuildResult(tenantId, scanned, rebuilt, failed, remaining);
    }

    /** 全租户回填（调度任务入口）：逐租户调用，任一租户失败不影响其它租户（下一轮重试）。 */
    public List<NameIndexRebuildResult> rebuildNameIndexAllTenants() {
        List<Long> tenantIds = nameTokenRepository.listTenantIdsWithMembers();
        List<NameIndexRebuildResult> results = new ArrayList<>();
        for (Long tenantId : tenantIds) {
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

    // ------------------------------------------------------------ 查询条件装配

    /**
     * 把控制层参数归一成端口的查询条件。
     *
     * <p>关键词的 token 与手机号摘要在这里算好：它们是应用层的加密/摘要口径
     * （{@link NameBlindIndex}），端口与适配器都不需要知道 token 是怎么来的。
     * 无租户上下文（同步/离线调用）时姓名通道不参与——盲索引是租户内检索，没有上下文就没有检索范围。
     */
    private MemberFilter filter(String keyword, Long level, String status, boolean onlyUnlinked,
                                Long originStoreId, TimeRange range, long offset, long limit) {
        if (originStoreId != null && originStoreId <= 0) {
            throw new ApiException(400, "STORE_FILTER_INVALID", "门店筛选非法");
        }
        String trimmed = keyword == null ? null : keyword.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        String digest = trimmed != null && isPhoneLike(trimmed) ? NameBlindIndex.sha256Hex(trimmed) : null;
        // 纯数字关键词走手机号摘要精确匹配：盲索引是"姓名/客户号"的索引，手机号根本没进 token 表，
        // 再查一次只会白跑一条 SQL（命中必然为空，OR 分支也会被跳过）。
        List<Long> blindIndexHits = digest != null ? List.of() : nameMatchIds(trimmed);
        return new MemberFilter(trimmed, digest, blindIndexHits, level, status, onlyUnlinked, originStoreId,
                range == null ? null : range.fromInclusive(),
                range == null ? null : range.toInclusive(),
                offset, limit);
    }

    /** 关键词的姓名/客户号盲索引候选档案 id 集合；无租户上下文（如同步/离线调用）时不走该通道。 */
    private List<Long> nameMatchIds(String keyword) {
        if (keyword == null) {
            return List.of();
        }
        Set<String> tokens = NameBlindIndex.tokenize(keyword);
        if (tokens.isEmpty()) {
            return List.of();
        }
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null) {
            return List.of();
        }
        return nameTokenRepository.findMemberIdsByAllTokens(tenantId, tokens);
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

    // ------------------------------------------------------------ 展示值装配

    /** 一页档案 + 展示值（账号类型 / IM 侧真实账号各一次批量查询，不逐行查库）。 */
    private List<MemberDisplay> describeAll(List<Member> members) {
        List<MemberDisplay> described = new ArrayList<>(members.size());
        for (Member member : members) {
            described.add(describePlain(member));
        }
        fillAccountTypes(described);
        fillImAccounts(described);
        return described;
    }

    /** 解密回填明文（name/phone）；无「客户隐私查看」权限时脱敏，密文仍保留在 nameCipher/phoneCipher。 */
    private MemberDisplay describePlain(Member member) {
        if (member == null) {
            return null;
        }
        boolean canViewPii = canViewPii();
        String name = aesGcmCipher.decrypt(member.nameCipher());
        String phone = aesGcmCipher.decrypt(member.phoneCipher());
        return MemberDisplay.withoutDisplay(member)
                .withDisplay(canViewPii ? name : maskName(name), canViewPii ? phone : maskPhone(phone),
                        null, null, null);
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

    /**
     * 批量补齐账号类型（同页一次查询）：取不到就不填（平台库不可达时不阻塞列表）。
     */
    private void fillAccountTypes(List<MemberDisplay> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> accountIds = new ArrayList<>();
        for (MemberDisplay record : records) {
            if (record.member().accountId() != null) {
                accountIds.add(record.member().accountId());
            }
        }
        if (accountIds.isEmpty()) {
            return;
        }
        try {
            Map<Long, String> types = imBindingRepository.findAccountTypes(accountIds);
            for (int i = 0; i < records.size(); i++) {
                MemberDisplay record = records.get(i);
                String type = types.get(record.member().accountId());
                if (type != null) {
                    records.set(i, record.withDisplay(record.name(), record.phone(), type,
                            record.imAccountName(), record.imAccountDeleted()));
                }
            }
        } catch (RuntimeException failure) {
            log.warn("补齐账号类型失败（不影响客户列表）: cause={}", failure.getMessage());
        }
    }

    /**
     * 批量补齐「IM 侧真实账号」（同页一次查询）：界面「IM 账号」列显示 {@code imAccountName}；
     * IM 用户已删除（用户行缺失）时置 {@code imAccountDeleted=true}，供页面标红并纳入清理判据。
     */
    private void fillImAccounts(List<MemberDisplay> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> memberIds = new ArrayList<>();
        Map<Long, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            Long memberId = records.get(i).member().id();
            if (memberId != null) {
                memberIds.add(memberId);
                indexOf.put(memberId, i);
            }
        }
        if (memberIds.isEmpty()) {
            return;
        }
        try {
            // 以客户档案为驱动表：档案没有 account_id（历史数据）也能按 im_account 解析出 IM 账号，
            // 且 IM 后台删掉 idt_login_identity 行后判定不会退化成「未删除」。
            for (ImBinding binding : imBindingRepository.resolveBindings(memberIds)) {
                Integer index = indexOf.get(binding.memberId());
                if (index == null) {
                    continue;
                }
                MemberDisplay record = records.get(index);
                records.set(index, record.withDisplay(record.name(), record.phone(), record.accountType(),
                        binding.imUsername(), binding.imAccountGone()));
            }
        } catch (RuntimeException failure) {
            log.warn("补齐 IM 真实账号失败（不影响客户列表）: cause={}", failure.getMessage());
        }
    }

    // ------------------------------------------------------------ 写路径辅助

    /**
     * 账号在统一账号模型里的 IM 登录标识（读不到就返回 {@code null}，绝不伪造）。
     * 查询失败同样返回 {@code null}：IM 侧读不到不该阻塞建档（员工也可以是消费者）。
     */
    private String bindingForAccount(Long accountId) {
        try {
            return imBindingRepository.findImAccountByAccountId(accountId);
        } catch (RuntimeException failure) {
            log.warn("读取账号 IM 登录标识失败（不阻塞建档）: accountId={}, cause={}", accountId, failure.getMessage());
            return null;
        }
    }

    /** 账号在统一账号模型里的最新 IM 昵称快照；失败返回 {@code null}。 */
    private String nicknameForAccount(Long accountId) {
        try {
            return imBindingRepository.findImNicknameByAccountId(accountId);
        } catch (RuntimeException failure) {
            log.warn("读取账号 IM 昵称失败（不阻塞建档）: accountId={}, cause={}", accountId, failure.getMessage());
            return null;
        }
    }

    /**
     * 重建一名客户的盲索引 token 集合（<b>先删后插</b>，幂等）。
     *
     * <p>token 覆盖「姓名（解密后）+ 客户号」两段文本；名字取自密文解密而不是入参，保证
     * create / im-binding / 回填三条路径用的是同一份口径。
     *
     * <p>失败补偿（删完旧的、插新的过程中失败时再删一次，退回「零 token 行」状态）在适配器内完成，
     * 因为它与"先删后插"必须是同一个操作：留下半套索引会被回填扫描误判为已完成。
     */
    private void rebuildNameTokens(Member member) {
        if (member == null || member.id() == null) {
            return;
        }
        Long tenantId = member.tenantId() != null ? member.tenantId() : TenantContextHolder.tenantIdOrNull();
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(NameBlindIndex.tokenize(aesGcmCipher.decrypt(member.nameCipher())));
        tokens.addAll(NameBlindIndex.tokenize(member.memberNo()));
        LocalDateTime now = LocalDateTime.now();
        List<MemberNameToken> rows = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            rows.add(MemberNameToken.of(tenantId, member.id(), token));
        }
        nameTokenRepository.replaceForMember(member.id(), rows, now);
    }

    /** 绑定留痕：成功与失败都要有；detail 只写客户号/客户 id 与 IM 账号，绝不带姓名/手机号。 */
    private void auditImBind(Member member, String imAccount, String result, String errorCode) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action(IM_BIND_ACTION)
                .resourceType("cst_member").resourceId(String.valueOf(member.id()))
                .resourceName(member.memberNo())
                .result(result)
                .errorCode(errorCode)
                .idempotencyKey("member-im-bind:" + member.id() + ":" + imAccount
                        + (errorCode == null ? "" : ":fail"))
                .detailJson("{\"memberNo\":\"" + member.memberNo() + "\",\"imAccount\":\"" + imAccount + "\"}")
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
}
