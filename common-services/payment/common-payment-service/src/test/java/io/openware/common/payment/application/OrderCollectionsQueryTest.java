package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import io.openware.common.payment.domain.closing.repository.DailyClosingRepository;
import io.openware.common.payment.domain.collect.model.PayCollect;
import io.openware.common.payment.domain.collect.repository.PayCollectRepository;
import io.openware.common.payment.domain.intent.model.PaymentIntent;
import io.openware.common.payment.domain.intent.repository.PaymentIntentRepository;
import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import io.openware.common.payment.domain.transaction.repository.PaymentTransactionRepository;
import io.openware.common.payment.domain.refund.model.Refund;
import io.openware.common.payment.domain.refund.repository.RefundRepository;
import io.openware.common.payment.domain.shift.repository.ShiftRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 订单收款明细读模型（{@code GET /business/payments/order-collections}）的口径回归。
 *
 * <p>门店反馈：订单管理里组合支付看不全——只看得到「已收」一个合计。这里钉住三件事：
 * <ol>
 *   <li><b>不遗漏分腿</b>：一次组合收款的每一腿各自成行（积分/储值/现金/支付宝/微信/Stripe），
 *       线上渠道绝不被并进「现金」；同一订单的多次收款逐笔保留，不合并成一行。</li>
 *   <li><b>不把尝试当收款</b>：只有 {@code CONFIRMED} 的收款计入；INIT/FAILED 不是收款明细。</li>
 *   <li><b>脏数据不炸页</b>：快照解析不了只跳过该条；混币种只给标记、不给单一币种金额。</li>
 * </ol>
 */
class OrderCollectionsQueryTest {

    private static final Long TENANT_ID = 100L;
    private static final Long ORDER_ID = 69L;

    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final PaymentIntentRepository paymentIntentRepository = mock(PaymentIntentRepository.class);
    private final DailyClosingRepository dailyClosingRepository = mock(DailyClosingRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final PayCollectRepository payCollectRepository = mock(PayCollectRepository.class);
    private final PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);

    private final PaymentQueryApplicationService service =
            new PaymentQueryApplicationService(shiftRepository, paymentIntentRepository, dailyClosingRepository, refundRepository,
                    payCollectRepository, paymentTransactionRepository);

    @BeforeEach
    void stubEmptyResults() {
        // TableInfo 不再需要：查询条件由适配器构造，本测试的替身是领域端口，不解析列名。
        when(paymentIntentRepository.listByOrderIds(any(), any())).thenReturn(List.of());
        when(paymentTransactionRepository.listByPaymentIntentIds(any(), any())).thenReturn(List.of());
        when(refundRepository.listByOrderIds(any(), any())).thenReturn(List.of());
    }

    @Test
    void combinedCollectionKeepsEveryLegIncludingOnlineChannel() {
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of(
                collect(1L, "PC-1", "CNY",
                        "{\"orderId\":69,\"customerId\":7,\"currencyCode\":\"CNY\",\"payable\":3900}",
                        "{\"remainingAmount\":0,\"collectedByMethod\":["
                                + "{\"method\":\"POINT\",\"amount\":300},"
                                + "{\"method\":\"WALLET\",\"amount\":600},"
                                + "{\"method\":\"ALIPAY\",\"amount\":2000},"
                                + "{\"method\":\"CASH\",\"amount\":1000}]}")));

        List<OrderCollectionsDto> result = service.orderCollections(TENANT_ID, List.of(ORDER_ID));

        assertEquals(1, result.size());
        OrderCollectionsDto view = result.get(0);
        assertEquals(ORDER_ID, view.orderId());
        assertEquals(3900L, view.totalCollected());
        assertEquals("CNY", view.currencyCode());
        assertFalse(view.mixedCurrency());
        assertEquals(1, view.collections().size());

        OrderCollectionsDto.Collection collection = view.collections().get(0);
        assertTrue(collection.combined(), "分腿 > 1 必须标为组合支付");
        assertEquals(3900L, collection.payable());
        assertEquals(7L, collection.customerId());
        assertEquals(
                List.of("POINT", "WALLET", "ALIPAY", "CASH"),
                collection.legs().stream().map(OrderCollectionsDto.Leg::method).toList(),
                "线上渠道必须保留自己的方式，不能被并进 CASH");
        assertEquals(
                List.of(300L, 600L, 2000L, 1000L),
                collection.legs().stream().map(OrderCollectionsDto.Leg::amount).toList());
    }

    @Test
    void multipleCollectionsOnOneOrderStaySeparate() {
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of(
                collect(1L, "PC-1", "USD",
                        "{\"orderId\":69,\"payable\":1000}",
                        "{\"collectedByMethod\":[{\"method\":\"CASH\",\"amount\":1000}]}"),
                collect(2L, "PC-2", "USD",
                        "{\"orderId\":69,\"payable\":500}",
                        "{\"collectedByMethod\":[{\"method\":\"WALLET\",\"amount\":500}]}")));

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertEquals(1500L, view.totalCollected(), "多次收款逐笔累加");
        assertEquals(2, view.collections().size(), "两笔收款不得合并成一行");
        assertFalse(view.collections().get(0).combined(), "单腿不是组合支付");
        assertEquals("PC-1", view.collections().get(0).collectNo());
        assertEquals("PC-2", view.collections().get(1).collectNo());
    }

    @Test
    void failedAndInitCollectionsAreNotPaymentDetail() {
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of(
                collect(1L, "PC-OK", "USD", "{\"payable\":800}",
                        "{\"collectedByMethod\":[{\"method\":\"CASH\",\"amount\":800}]}")));

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertEquals(1, view.collections().size());
        assertEquals(800L, view.totalCollected());
        // 只读已确认收款：INIT/HOLD/FAILED 的尝试不算收款。分层后这由**端口方法本身**承载
        // （listConfirmedByOrderIds），调用方无法表达"把尝试也算进来"。
        verify(payCollectRepository).listConfirmedByOrderIds(eq(TENANT_ID), eq(List.of(ORDER_ID)));
    }

    @Test
    void tenantAndOrderScopeArePushedIntoTheQuery() {
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of());

        service.orderCollections(TENANT_ID, List.of(ORDER_ID, 70L));

        // 读资金明细必须显式带租户与订单范围：端口签名要求两者，调用方无法省略。
        verify(payCollectRepository).listConfirmedByOrderIds(TENANT_ID, List.of(ORDER_ID, 70L));
    }

    @Test
    void unparseableSnapshotDoesNotBreakTheWholePage() {
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of(
                collect(1L, "PC-BAD", "USD", "not-json", "{\"also\":\"broken\""),
                collect(2L, "PC-OK", "USD", "{\"payable\":900}",
                        "{\"collectedByMethod\":[{\"method\":\"CASH\",\"amount\":900}]}")));

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertEquals(2, view.collections().size(), "脏快照只跳过自身，不影响同订单其它收款");
        assertEquals(0L, view.collections().get(0).payable());
        assertTrue(view.collections().get(0).legs().isEmpty());
        assertEquals(900L, view.totalCollected());
    }

    @Test
    void zeroAmountLegsAreDroppedAndSingleLegIsNotCombined() {
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of(
                collect(1L, "PC-1", "USD", "{\"payable\":900}",
                        "{\"collectedByMethod\":[{\"method\":\"CASH\",\"amount\":900},"
                                + "{\"method\":\"POINT\",\"amount\":0}]}")));

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertEquals(1, view.collections().get(0).legs().size(), "零金额分腿不是一次实际支付");
        assertFalse(view.collections().get(0).combined());
    }

    @Test
    void mixedCurrencyIsFlaggedAndNoSingleCurrencyIsGiven() {
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of(
                collect(1L, "PC-CNY", "CNY", "{\"payable\":100}",
                        "{\"collectedByMethod\":[{\"method\":\"CASH\",\"amount\":100}]}"),
                collect(2L, "PC-USD", "USD", "{\"payable\":200}",
                        "{\"collectedByMethod\":[{\"method\":\"CASH\",\"amount\":200}]}")));

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertTrue(view.mixedCurrency());
        assertNull(view.currencyCode(), "混币种不得给单一币种，否则前端会把两种币相加");
        assertEquals("CNY", view.collections().get(0).currencyCode(), "每笔收款仍保留自己的币种快照");
    }

    @Test
    void transactionsCarryProviderAndChannelReference() {
        PaymentIntent intent = new PaymentIntent(31L, TENANT_ID, 1L, ORDER_ID, null, "ALIPAY", "ALIPAY",
                new BigDecimal("2000"), "CNY", "SUCCEEDED", null, null,
                LocalDateTime.of(2026, 9, 19, 12, 0), LocalDateTime.of(2026, 9, 19, 12, 0));
        when(paymentIntentRepository.listByOrderIds(any(), any())).thenReturn(List.of(intent));

        PaymentTransaction tx = new PaymentTransaction(88L, TENANT_ID, 1L, "KTV", 31L, "ALIPAY",
                "2026091922001400000001", null, new BigDecimal("2000"), "CNY", null, null, "SUCCEEDED",
                LocalDateTime.of(2026, 9, 19, 12, 0, 1), null);
        when(paymentTransactionRepository.listByPaymentIntentIds(any(), any())).thenReturn(List.of(tx));
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of());

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertEquals(1, view.transactions().size());
        assertEquals("ALIPAY", view.transactions().get(0).provider());
        assertEquals("2026091922001400000001", view.transactions().get(0).providerTransactionNo(),
                "线上渠道交易号是对账凭据，不能丢");
        assertEquals(2000L, view.transactions().get(0).amount());
    }

    @Test
    void refundsAreListedAndOnlyRefundedMoneyCountsAsRefunded() {
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of());
        when(refundRepository.listByOrderIds(any(), any())).thenReturn(List.of(
                refund(1L, "REFUNDED", "1000", "1000"),
                refund(2L, "APPROVED", "500", "500"),
                refund(3L, "REJECTED", "300", null)));

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertEquals(3, view.refunds().size(), "退款记录一条都不能少（含被拒的）");
        assertEquals(1000L, view.refundedAmount(), "只有 REFUNDED 的钱算已退款");
        assertEquals("REFUNDED", view.refunds().get(0).status());
    }

    @Test
    void ordersWithoutAnyPaymentDataAreAbsentInsteadOfZero() {
        when(payCollectRepository.listConfirmedByOrderIds(any(), any())).thenReturn(List.of());

        List<OrderCollectionsDto> result = service.orderCollections(TENANT_ID, List.of(ORDER_ID, 70L));

        assertTrue(result.isEmpty(), "未收款的订单不出现在结果里，前端据此显示「未收款」");
    }

    @Test
    void emptyInputsReturnEmptyWithoutQuerying() {
        assertTrue(service.orderCollections(null, List.of(ORDER_ID)).isEmpty());
        assertTrue(service.orderCollections(TENANT_ID, List.of()).isEmpty());
        assertTrue(service.orderCollections(TENANT_ID, null).isEmpty());
    }

    // ------------------------------------------------------------------ 测试夹具

    private PayCollect collect(Long id, String collectNo, String currency, String requestJson, String responseJson) {
        return new PayCollect(id, TENANT_ID, 1L, ORDER_ID, collectNo, PayCollect.STATE_CONFIRMED, currency,
                requestJson, responseJson, LocalDateTime.of(2026, 9, 19, 12, 0).plusMinutes(id));
    }

    private Refund refund(Long id, String status, String requested, String approved) {
        return new Refund(id, TENANT_ID, 1L, "KTV", ORDER_ID, null, "req-" + id,
                new BigDecimal(requested), approved == null ? null : new BigDecimal(approved), "CNY", null,
                status, null, 7L, null, LocalDateTime.of(2026, 9, 19, 13, 0).plusMinutes(id), null);
    }

    /** 已确认收款必须是**按订单批量查询**的那一次调用：非 CONFIRMED 的尝试不得算进已收金额。 */
    @SuppressWarnings("unchecked")
    private List<Long> capturedCollectOrderIds() {
        org.mockito.ArgumentCaptor<List<Long>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(payCollectRepository, org.mockito.Mockito.atLeastOnce())
                .listConfirmedByOrderIds(org.mockito.ArgumentMatchers.any(), captor.capture());
        return captor.getValue();
    }
}
