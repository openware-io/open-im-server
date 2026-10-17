package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.platform.tenant.application.WalletTokenConfigApplicationService;
import io.openware.platform.tenant.application.WalletTokenConfigApplicationService.TenantWalletTokenConfig;
import org.springframework.web.bind.annotation.*;

/**
 * 租户级钱包代币（储值品牌）配置：代币展示名 + 主单位与代币的比例（默认 1:100）。
 *
 * <p><b>ratio 语义（冻结，见 16_CURRENCY_CONVENTIONS §9）</b>：{@code ratio = N} 表示
 * <b>1 个主单位（1 元 / 1 美元，随租户币种，默认 USD）= N 代币</b>。
 * 该比例<b>仅用于展示与文案</b>（例如界面提示「充 100 元得 10,000 A380币」），
 * <b>硬约束：禁止参与任何入账 / 扣减 / 对账 / 退款 / 日结计算</b>——钱包账本、支付、退款、班次日结
 * 一律只用<b>最小货币单位</b>（CNY 分 / USD cent）的整数金额，不按 ratio 折算；跨币种不做折算（§1）。
 * 本接口只做键值读写，不引入任何换算逻辑。
 *
 * <p>读取：租户一律取自签名上下文（不信任请求体/查询参数），仅能读自己租户的配置，
 * 这样账单/支付/充值各端（含 C 端 H5）都能拿到展示名而不泄露其他租户配置。
 * <p>写入：权限、租户边界、落库与审计在 {@link WalletTokenConfigApplicationService#update}；
 * 本类只做协议转换。
 */
@RestController
@RequestMapping("/admin/tenant/config")
public class TenantConfigController {

    private final WalletTokenConfigApplicationService walletTokenConfigService;

    public TenantConfigController(WalletTokenConfigApplicationService walletTokenConfigService) {
        this.walletTokenConfigService = walletTokenConfigService;
    }

    /**
     * 当前租户的钱包代币展示名与比例（租户来自上下文）。
     *
     * <p>{@code ratio} 是展示口径：1 个主单位（随租户币种）= ratio 个代币；调用方<b>不得</b>用它做金额换算。
     * 缺行/空值/非法值一律回退 {@code A380币 / 100}（与内部端点同源，读路径不抛错）。
     */
    @GetMapping
    public WalletTokenConfig get(@RequestParam(required = false) Long tenantId) {
        TenantWalletTokenConfig config = walletTokenConfigService.resolveScoped(tenantId);
        return new WalletTokenConfig(config.brandName(), config.ratio());
    }

    /** 修改展示名与比例（经营权限 + 同租户）。 */
    @PutMapping
    public WalletTokenConfig update(@RequestBody UpdateRequest req) {
        if (req == null) {
            throw new ApiException(400, "CONFIG_REQUIRED", "配置不能为空");
        }
        TenantWalletTokenConfig config = walletTokenConfigService.update(req.tenantId(), req.brandName(), req.ratio());
        return new WalletTokenConfig(config.brandName(), config.ratio());
    }

    public record WalletTokenConfig(String brandName, long ratio) {}
    public record UpdateRequest(Long tenantId, String brandName, long ratio) {}
}
