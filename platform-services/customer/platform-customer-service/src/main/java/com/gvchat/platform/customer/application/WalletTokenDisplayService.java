package com.gvchat.platform.customer.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.customer.infra.client.TenantWalletTokenClient;
import com.gvchat.platform.customer.infra.client.TenantWalletTokenClient.WalletTokenConfig;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletAccountPo;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * 钱包响应的「代币数量」展示口径——<b>服务端唯一换算点</b>（C 端 / 后台 / B 端不再各自按 ratio 换算）。
 *
 * <p><b>换算公式（已确认口径）</b>：
 * {@code 代币数量 = 余额最小货币单位 ÷ 100 × 租户比例 wallet_ratio}，结果四舍五入取整；ratio 缺省 100。
 * 例：可用余额 100（USD cent，= 1 美元）× ratio 100 → {@code 100} 个代币。
 *
 * <p><b>口径边界</b>：
 * <ul>
 *   <li>只有<b>现金与价格</b>才带货币单位/币种（现金默认 USD）；储值币/代币是组合支付里的一种支付工具，
 *       界面显示<b>数量</b>，<b>不得</b>出现货币符号或币种——故 {@code tokenAmount} 是纯数字字符串，
 *       且响应里同时给出品牌展示名 {@code tokenBrandName}（取自租户配置 wallet_brand_name）。</li>
 *   <li>原有货币字段（{@code availableAmount} / {@code frozenAmount} / {@code amount} / {@code balanceAfter} /
 *       {@code currencyCode}）<b>原样保留</b>：它们仍是「这笔钱」的最小货币单位与币种，用于对账与入账。</li>
 *   <li>代币数量<b>只用于展示</b>：入账、扣减、退款、对账、日结一律以最小货币单位整数金额为准，
 *       {@code tokenAmount} 绝不参与任何金额合计。</li>
 *   <li>积分（{@code cst_point_*}）不是代币：积分按「个数」1:1 呈现，不加币种、也不加 tokenAmount。</li>
 * </ul>
 */
@Service
public class WalletTokenDisplayService {

    /**
     * 1 个主单位包含的最小货币单位数：CNY 分 / USD cent 均为 1/100 主单位
     * （`docs/standards/16_CURRENCY_CONVENTIONS.md` §1 当前只支持 CNY/USD）；
     * 若未来引入非 2 位小数币种，需改为按币种解析最小单位位数。
     */
    private static final BigDecimal MINOR_UNITS_PER_MAJOR = BigDecimal.valueOf(100L);

    private final TenantWalletTokenClient tenantWalletTokenClient;

    public WalletTokenDisplayService(TenantWalletTokenClient tenantWalletTokenClient) {
        this.tenantWalletTokenClient = tenantWalletTokenClient;
    }

    /**
     * 当前租户的代币展示配置：租户来自签名上下文；无上下文或调用失败一律回退缺省
     * {@code A380币 / 100}（由 {@link TenantWalletTokenClient} 保证不抛异常）。
     */
    public WalletTokenConfig currentConfig() {
        TenantContext context = TenantContextHolder.get();
        return tenantWalletTokenClient.resolve(context == null ? null : context.tenantId());
    }

    /**
     * 最小货币单位金额 → 代币数量（整数、四舍五入 HALF_UP、字符串）。
     *
     * @param minorAmount 最小货币单位金额（USD cent / CNY 分）；null 按 0
     * @param ratio       租户比例（1 个主单位 = ratio 个代币）；非正数回退缺省 100
     * @return 代币数量字符串（不带千分位、不带货币符号、不显示等值货币）
     */
    public String tokenAmount(Long minorAmount, long ratio) {
        long amount = minorAmount == null ? 0L : minorAmount;
        long safeRatio = ratio > 0 ? ratio : TenantWalletTokenClient.DEFAULT_RATIO;
        return BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(safeRatio))
                .divide(MINOR_UNITS_PER_MAJOR, 0, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * 为钱包账户响应补充代币展示字段（{@code tokenAmount} / {@code tokenBrandName}）与
     * {@code accountOpened}（是否已开立真实账户行：{@code id != null}）。
     * 货币字段（availableAmount / frozenAmount / currencyCode）原样不动；冻结金额不影响可用代币口径。
     *
     * <p>懒初始化下「没有账户」= 零额只读视图（{@code id == null}）：此时 {@code accountOpened=false}，
     * 界面可显示「未开立」而不是把它当成一个余额为 0 的真实账户。
     */
    public CstWalletAccountPo decorate(CstWalletAccountPo account) {
        if (account == null) {
            return null;
        }
        WalletTokenConfig config = currentConfig();
        account.setTokenBrandName(config.brandName());
        account.setTokenAmount(tokenAmount(account.getAvailableAmount(), config.ratio()));
        account.setAccountOpened(account.getId() != null);
        return account;
    }

    /**
     * 为钱包流水分页补充代币展示字段：每行按本笔发生额（{@code amount}）换算数量。
     * 金额字段（amount / balanceAfter / currencyCode）原样不动。
     */
    public Page<CstWalletLedgerPo> decorate(Page<CstWalletLedgerPo> page) {
        if (page == null || page.getRecords() == null) {
            return page;
        }
        WalletTokenConfig config = currentConfig();
        for (CstWalletLedgerPo row : page.getRecords()) {
            row.setTokenBrandName(config.brandName());
            row.setTokenAmount(tokenAmount(row.getAmount(), config.ratio()));
        }
        return page;
    }
}
