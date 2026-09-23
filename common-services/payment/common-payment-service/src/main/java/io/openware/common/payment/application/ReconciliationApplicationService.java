package io.openware.common.payment.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.common.payment.infra.persistence.mapper.PayTransactionMapper;
import io.openware.common.payment.infra.persistence.po.PayTransactionPo;
import io.openware.infrastructure.currency.Currency;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对账摘要：按「支付渠道 + 币种」汇总成功交易（笔数/毛额/手续费/净额）。
 * 完整对账（回调验签、渠道对账文件比对）见后续专项。
 *
 * <p><b>币种（16_CURRENCY_CONVENTIONS §5）</b>：分组键加入币种快照
 * {@code pay_transaction.currency_code}——把 CNY 与 USD 的毛额/手续费静默相加会得出一个没有业务含义的
 * 「净额」，因此每个渠道按币种各出一行；信封上再给出单币种 {@code currencyCode} 与 {@code mixedCurrency}
 * 标记。混币种时 {@code currencyCode=null}，{@code total} 行同样以 {@code currencyCode=null} 显式标注
 * 「这是一个跨币种合计」，绝不当成单一币种金额使用。
 */
@Service
public class ReconciliationApplicationService {
    private final PayTransactionMapper payTransactionMapper;

    public ReconciliationApplicationService(PayTransactionMapper payTransactionMapper) { this.payTransactionMapper = payTransactionMapper; }

    public ReconciliationSummary summary(Long tenantId, LocalDateTime from, LocalDateTime to) {
        QueryWrapper<PayTransactionPo> qw = new QueryWrapper<PayTransactionPo>()
                .eq("tenant_id", tenantId).eq("status", "SUCCEEDED")
                .ge("occurred_at", from).lt("occurred_at", to);
        List<PayTransactionPo> txs = payTransactionMapper.selectList(qw);

        Map<String, ProviderLine> byProviderCurrency = new LinkedHashMap<>();
        Set<String> currencies = new LinkedHashSet<>();
        long totalCount = 0;
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        for (PayTransactionPo tx : txs) {
            BigDecimal fee = tx.getFeeAmount() == null ? BigDecimal.ZERO : tx.getFeeAmount();
            String provider = tx.getProvider();
            String currencyCode = Currency.parse(tx.getCurrencyCode()).code();
            String groupKey = provider + "|" + currencyCode;
            ProviderLine prev = byProviderCurrency.get(groupKey);
            ProviderLine next = prev == null
                    ? new ProviderLine(provider, currencyCode, 1, tx.getAmount(), fee, tx.getAmount().subtract(fee))
                    : new ProviderLine(provider, currencyCode, prev.count() + 1,
                                       prev.grossAmount().add(tx.getAmount()),
                                       prev.feeAmount().add(fee), prev.netAmount().add(tx.getAmount()).subtract(fee));
            byProviderCurrency.put(groupKey, next);
            currencies.add(currencyCode);
            totalCount++;
            totalGross = totalGross.add(tx.getAmount());
            totalFee = totalFee.add(fee);
        }
        String currencyCode = currencies.size() == 1 ? currencies.iterator().next() : null;
        boolean mixedCurrency = currencies.size() > 1;
        ProviderLine total = new ProviderLine("TOTAL", currencyCode, totalCount, totalGross, totalFee,
                totalGross.subtract(totalFee));
        return new ReconciliationSummary(from, to, currencyCode, mixedCurrency,
                new ArrayList<>(byProviderCurrency.values()), total);
    }

    /**
     * 渠道汇总行。
     *
     * @param currencyCode 本行金额的币种快照；{@code total} 行在混币种时为 {@code null}（= 跨币种合计，不可当单币种用）
     */
    public record ProviderLine(String provider, String currencyCode, long count, BigDecimal grossAmount,
                               BigDecimal feeAmount, BigDecimal netAmount) {}

    /**
     * 对账摘要信封。
     *
     * @param currencyCode 单币种时即该币种；混币种时为 {@code null}
     * @param mixedCurrency 是否存在多种币种（true 时调用方必须按行内 currencyCode 分别展示，不得相加）
     */
    public record ReconciliationSummary(LocalDateTime from, LocalDateTime to, String currencyCode, boolean mixedCurrency,
                                        List<ProviderLine> lines, ProviderLine total) {}
}
