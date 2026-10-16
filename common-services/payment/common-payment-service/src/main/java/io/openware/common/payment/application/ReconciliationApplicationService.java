package io.openware.common.payment.application;

import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import io.openware.common.payment.domain.transaction.repository.PaymentTransactionRepository;
import io.openware.common.payment.domain.transaction.service.ReconciliationSummaryBuilder;
import io.openware.common.payment.domain.transaction.service.ReconciliationSummaryBuilder.ReconciliationSummary;
import io.openware.infrastructure.currency.Currency;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账摘要：按「支付渠道 + 币种」汇总成功交易（笔数/毛额/手续费/净额）。
 * 完整对账（回调验签、渠道对账文件比对）见后续专项。
 *
 * <p><b>币种（16_CURRENCY_CONVENTIONS §5）</b>：分组键加入币种快照
 * {@code pay_transaction.currency_code}——把 CNY 与 USD 的毛额/手续费静默相加会得出一个没有业务含义的
 * 「净额」，因此每个渠道按币种各出一行；信封上再给出单币种 {@code currencyCode} 与 {@code mixedCurrency}
 * 标记。混币种时 {@code currencyCode=null}，{@code total} 行同样以 {@code currencyCode=null} 显式标注
 * 「这是一个跨币种合计」，绝不当成单一币种金额使用。
 *
 * <p>币种归一（空值/小写 → 规范编码）在这里做：它是外部数据形状的处理；
 * 汇总本身在 {@link ReconciliationSummaryBuilder}。
 */
@Service
public class ReconciliationApplicationService {
    private final PaymentTransactionRepository transactionRepository;

    public ReconciliationApplicationService(PaymentTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public ReconciliationSummary summary(Long tenantId, LocalDateTime from, LocalDateTime to) {
        List<PaymentTransaction> txs = transactionRepository.listSucceededBetween(tenantId, from, to)
                .stream()
                // 币种归一：同一币种的不同写法若被当成两个值，会把一个渠道拆成两行。
                .map(tx -> new PaymentTransaction(tx.id(), tx.tenantId(), tx.storeId(), tx.businessType(),
                        tx.paymentIntentId(), tx.provider(), tx.providerTransactionNo(),
                        tx.providerPayloadDigest(), tx.amount(), Currency.parse(tx.currencyCode()).code(),
                        tx.exchangeRate(), tx.feeAmount(), tx.status(), tx.occurredAt(), tx.rawReference()))
                .toList();
        return ReconciliationSummaryBuilder.summarize(txs, from, to);
    }
}
