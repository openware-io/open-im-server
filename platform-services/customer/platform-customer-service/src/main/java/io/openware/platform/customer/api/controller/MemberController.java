package io.openware.platform.customer.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.platform.customer.application.MemberApplicationService;
import io.openware.platform.customer.application.MemberApplicationService.MemberPointsRow;
import io.openware.platform.customer.application.MemberPointsView;
import io.openware.platform.customer.application.NameIndexRebuildResult;
import io.openware.platform.customer.application.PointApplicationService;
import io.openware.platform.customer.application.WalletApplicationService;
import io.openware.platform.customer.application.WalletTokenDisplayService;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 客户与营销 API（对齐 SAAS_PLATFORM_05_API.md §8 冻结契约）。表名仍是 cst_member，业务口径是「客户」。
 * 网关统一加 /api/v1 前缀，本服务只暴露 /business/**。
 *
 * <p>钱包响应（余额 / 我的资产 / 会员钱包 / 储值流水）新增展示字段 {@code tokenAmount}
 * （代币数量，字符串整数，不带币种/货币符号）与 {@code tokenBrandName}（代币品牌展示名）；
 * 原货币字段（availableAmount / frozenAmount / amount / currencyCode）语义不变，仍用于对账入账。
 * 积分响应只给积分「个数」，不含币种、不含 tokenAmount。
 */
@RestController
@RequestMapping("/business/members")
public class MemberController {
    private final MemberApplicationService memberService;
    private final PointApplicationService pointService;
    private final WalletApplicationService walletService;
    private final WalletTokenDisplayService walletTokenDisplayService;

    public MemberController(MemberApplicationService memberService,
                            PointApplicationService pointService,
                            WalletApplicationService walletService,
                            WalletTokenDisplayService walletTokenDisplayService) {
        this.memberService = memberService;
        this.pointService = pointService;
        this.walletService = walletService;
        this.walletTokenDisplayService = walletTokenDisplayService;
    }

    /**
     * 客户分页列表（仅当前租户；列表响应已带 imAccount / imUsername，后台「详情」直接复用该行）。
     *
     * <p><b>时间区间</b>：{@code from}/{@code to} 按**建档时间** {@code joined_at} 的闭区间筛选，
     * 统一口径见 {@link TimeRangeParams}：接受 {@code yyyy-MM-dd}（from 取当天 00:00:00.000、
     * to 取当天 23:59:59.999）与 {@code yyyy-MM-ddTHH:mm:ss}；为空 = 不筛；
     * {@code from > to} → 400 {@code TIME_RANGE_INVALID}。
     *
     * <p><b>垃圾档案筛选</b>：{@code onlyUnlinked}（默认 {@code false}）为 true 时只返回**没有 IM 关联**
     * 的客户档案（{@code cst_member.im_account IS NULL}，与 {@code MemberPurgeApplicationService}
     * 的清理判据同一口径）。该参数与服务层 {@code MemberApplicationService#list(..., boolean)}
     * 是**同一个**实现（{@code qw.isNull(CstMemberPo::getImAccount)}），没有第二套过滤 SQL；
     * 其余筛选参数（keyword/level/status/from/to）与分页、租户行过滤一律不变。
     *
     * <p><b>口径边界</b>：{@code im_account} 有值但 IM 侧用户**已删除**的档案不属于 {@code onlyUnlinked}
     * ——那是「关联已失效」，不是「从未关联」；这类行仍由列表响应里的展示字段
     * {@code imAccountDeleted} 单独标记，未并入本参数（需要单独筛时再单独加参数，不要重载本参数）。
     */
    @GetMapping
    public Page<CstMemberPo> list(@RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "20") long pageSize,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) Long level,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(defaultValue = "false") boolean onlyUnlinked) {
        if (!onlyUnlinked) {
            // false / 未传：走既有 7 参数重载（服务层 6 参数口径）——与加本参数之前**完全同一条调用路径**，
            // 不因为多了一个参数就改变「不筛」时的服务层入口（既有断言的就是这条调用）。
            return list(page, pageSize, keyword, level, status, from, to);
        }
        return memberService.list(page, pageSize, keyword, level, status, TimeRangeParams.parse(from, to), true);
    }

    /**
     * 兼容既有 7 参数调用：等价于 {@code onlyUnlinked=false}（不筛「无 IM 关联」）。
     *
     * <p>HTTP 入口是上面的 8 参数重载；本方法**不再标注映射注解**，否则同一路径会注册出两个处理器
     * （启动即 ambiguous mapping）。保留它只是为了让既有调用方（含控制器用例）签名不变。
     */
    public Page<CstMemberPo> list(long page, long pageSize, String keyword, Long level, String status,
                                  String from, String to) {
        return memberService.list(page, pageSize, keyword, level, status, TimeRangeParams.parse(from, to));
    }

    /**
     * 创建客户 / 未验证手机号创建待认领客户 / 绑定 IM 建档。
     *
     * <p><b>幂等</b>：请求体里的 {@code accountId} 或 {@code imAccount} 只要已存在客户，就**返回那条既有客户**
     * 而不是新建（200，不抛 500）——这是「防止同一 IM 用户生成多条客户」的第一道闸。
     */
    @PostMapping
    public CstMemberPo create(@RequestBody CreateMemberRequest req) {
        return memberService.create(req.accountId(), req.name(), req.phone(), req.consent(),
                req.imAccount(), req.imUsername());
    }

    /**
     * 把既有客户绑定到 IM 账号（{@code imAccount} 已被另一条客户占用 → 409 {@code IM_ACCOUNT_ALREADY_BOUND}）。
     *
     * <p>可选 {@code name}：一起改姓名时同步重建姓名盲索引（改姓名必须重建 token）。
     */
    @PutMapping("/{id}/im-binding")
    public CstMemberPo bindIm(@PathVariable Long id, @RequestBody ImBindingRequest req) {
        return memberService.bindIm(id, req.imAccount(), req.imUsername(), req.name());
    }

    /**
     * 姓名盲索引存量回填（人工触发入口；定时任务 {@code MemberNameIndexRebuildJob} 走同一服务方法）。
     *
     * <p>权限用 {@code member.pii.view}：回填必须解密姓名密文（与「看姓名明文」是同一份 PII），
     * 而 {@code tenant.tenant.manage} 是经营配置权限、与 PII 无关，不该顺带获得接触姓名的能力。
     * 不传 {@code tenantId} 时只回填**当前上下文租户**；传了其它租户则要求平台作用域上下文。
     */
    @PostMapping("/name-index/rebuild")
    public NameIndexRebuildResult rebuildNameIndex(@RequestBody(required = false) NameIndexRebuildRequest req) {
        PermissionGuard.require(MemberApplicationService.PII_PERMISSION);
        TenantContext ctx = TenantContextHolder.get();
        if (ctx == null) {
            throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + MemberApplicationService.PII_PERMISSION);
        }
        Long requested = req == null ? null : req.tenantId();
        if (requested != null && requested != ctx.tenantId() && !ctx.platformScope()) {
            throw new ApiException(403, "TENANT_SCOPE_MISMATCH", "只能回填当前租户的姓名盲索引");
        }
        long target = requested == null ? ctx.tenantId() : requested;
        return memberService.rebuildNameIndexForTenant(target);
    }

    /** C 端「我的资产」入口：按当前账号找/建会员；储值账户懒初始化（没有账户=余额 0，不落库、不报错）。 */
    @GetMapping("/me")
    public MemberMeResponse me() {
        TenantContext ctx = TenantContextHolder.get();
        Long accountId = ctx == null ? null : ctx.accountId();
        if (accountId == null) {
            throw new ApiException(401, "ACCOUNT_REQUIRED", "缺少账号上下文");
        }
        CstMemberPo member = memberService.getOrCreateByAccount(accountId);
        // 币种不再硬编码 CNY：取当时租户币种（缺省 USD，16_CURRENCY_CONVENTIONS §1/§2）。
        // 钱包响应补代币数量/品牌展示字段；积分响应只给积分「个数」（无币种、无代币字段）。
        // 储值走懒初始化读路径（balance）：没有账户即余额 0，不因为「看了一眼资产」就给会员建空账户。
        CstWalletAccountPo wallet = walletTokenDisplayService.decorate(
                walletService.balance(member.getId()));
        CstPointAccountPo points = pointService.ensureAccount(member.getId());
        return new MemberMeResponse(member.getId(), member.getMemberNo(), wallet, points);
    }

    /**
     * 积分管理列表：客户 + 积分账户余额（租户级积分管理板块）。
     *
     * <p><b>时间区间</b>：{@code from}/{@code to} 按**建档时间** {@code joined_at} 的闭区间筛选
     * （行主体是客户，业务时间列就是建档时间），口径见 {@link TimeRangeParams}。
     */
    @GetMapping("/points")
    public Page<MemberPointsRow> pointsList(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long pageSize,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        return memberService.pointsList(page, pageSize, keyword, TimeRangeParams.parse(from, to));
    }

    /** 会员积分：积分账户 + 账本分页。 */
    @GetMapping("/{id}/points")
    public MemberPointsView points(@PathVariable Long id,
                                   @RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "20") long pageSize) {
        return pointService.view(id, page, pageSize);
    }

    /** 积分调整（高风险审计，命令幂等 commandId）。 */
    @PostMapping("/{id}/points/adjust")
    public CstPointAccountPo adjustPoints(@PathVariable Long id, @RequestBody AdjustPointsRequest req) {
        return pointService.adjust(id, req.points(), req.reason(), req.commandId());
    }

    /** 会员储值余额（同主体同币种，租户级、跨门店共用）+ 代币展示数量。没有账户即余额 0（不创建账户）。 */
    @GetMapping("/{id}/wallet")
    public CstWalletAccountPo wallet(@PathVariable Long id) {
        return walletTokenDisplayService.decorate(walletService.balance(id));
    }

    /**
     * 会员储值列表（「储值管理」页）：一次会员分页 + 一次批量余额查询，<b>不逐会员查余额</b>
     * （既避免 N+1，也不会因为没有储值账户而产生「储值账户不存在」的错误）。
     *
     * <p>没有账户的会员按 0 返回且 {@code accountOpened=false}：储值账户是<b>租户级</b>资产，
     * 只在首次充值时开立，绝大多数会员本来就没有账户。
     *
     * <p><b>时间区间</b>：{@code from}/{@code to} 按**建档时间** {@code joined_at} 的闭区间筛选，
     * 与客户列表/积分列表同一个 {@code cst_member} 查询（同一实现，口径不可能漂移）；
     * 分页在客户行上，{@code total} = 命中客户数，与过滤条件一致。
     */
    @GetMapping("/wallets")
    public Page<MemberWalletRow> wallets(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long pageSize,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        Page<CstMemberPo> members = memberService.list(page, pageSize, keyword, null, null,
                TimeRangeParams.parse(from, to));
        List<Long> memberIds = members.getRecords().stream().map(CstMemberPo::getId).toList();
        Map<Long, CstWalletAccountPo> balances = walletService.balances(memberIds);
        long ratio = walletTokenDisplayService.currentConfig().ratio();
        List<MemberWalletRow> rows = members.getRecords().stream().map(member -> {
            CstWalletAccountPo account = balances.get(member.getId());
            long available = account == null || account.getAvailableAmount() == null
                    ? 0L : account.getAvailableAmount();
            long frozen = account == null || account.getFrozenAmount() == null
                    ? 0L : account.getFrozenAmount();
            return new MemberWalletRow(member.getId(), member.getMemberNo(), member.getName(), member.getPhone(),
                    available, frozen, walletTokenDisplayService.tokenAmount(available, ratio),
                    account == null ? CurrencyResolver.currentCode() : account.getCurrencyCode(),
                    account != null);
        }).toList();
        Page<MemberWalletRow> result = new Page<>(page, pageSize, members.getTotal());
        result.setRecords(rows);
        return result;
    }

    /** 会员储值账本（C 端明细）+ 每笔代币展示数量。 */
    @GetMapping("/{id}/wallet/ledger")
    public Page<CstWalletLedgerPo> walletLedger(@PathVariable Long id,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long pageSize) {
        return walletTokenDisplayService.decorate(walletService.ledger(id, page, pageSize));
    }

    public record CreateMemberRequest(Long accountId, String name, String phone, Boolean consent,
                                      String imAccount, String imUsername) {}
    /** IM 绑定请求：{@code name} 可选，传了就连同姓名一起更新并重建盲索引。 */
    public record ImBindingRequest(String imAccount, String imUsername, String name) {}
    /** 姓名盲索引回填请求：{@code tenantId} 省略 = 当前上下文租户。 */
    public record NameIndexRebuildRequest(Long tenantId) {}
    public record AdjustPointsRequest(Long points, String reason, String commandId) {}
    public record MemberMeResponse(Long memberId, String memberNo, CstWalletAccountPo wallet, CstPointAccountPo points) {}

    /** 储值管理列表行：会员 + 余额（最小货币单位）+ 代币展示数量；没有账户时余额 0、{@code accountOpened=false}。 */
    public record MemberWalletRow(Long memberId, String memberNo, String name, String phone,
                                  Long availableAmount, Long frozenAmount, String tokenAmount,
                                  String currencyCode, Boolean accountOpened) {}
}
