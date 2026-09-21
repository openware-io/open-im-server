package com.gvchat.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import com.gvchat.common.payment.infra.persistence.mapper.DailyClosingMapper;
import com.gvchat.common.payment.infra.persistence.mapper.PayCollectMapper;
import com.gvchat.common.payment.infra.persistence.mapper.PayIntentMapper;
import com.gvchat.common.payment.infra.persistence.mapper.PayTransactionMapper;
import com.gvchat.common.payment.infra.persistence.mapper.RefundMapper;
import com.gvchat.common.payment.infra.persistence.mapper.ShiftMapper;
import com.gvchat.common.payment.infra.persistence.po.DailyClosingPo;
import com.gvchat.common.payment.infra.persistence.po.PayCollectPo;
import com.gvchat.common.payment.infra.persistence.po.PayIntentPo;
import com.gvchat.common.payment.infra.persistence.po.PayTransactionPo;
import com.gvchat.common.payment.infra.persistence.po.RefundPo;
import com.gvchat.common.payment.infra.persistence.po.ShiftPo;
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

    private final ShiftMapper shiftMapper = mock(ShiftMapper.class);
    private final PayIntentMapper payIntentMapper = mock(PayIntentMapper.class);
    private final DailyClosingMapper dailyClosingMapper = mock(DailyClosingMapper.class);
    private final RefundMapper refundMapper = mock(RefundMapper.class);
    private final PayCollectMapper payCollectMapper = mock(PayCollectMapper.class);
    private final PayTransactionMapper payTransactionMapper = mock(PayTransactionMapper.class);

    private final PaymentQueryApplicationService service =
            new PaymentQueryApplicationService(shiftMapper, payIntentMapper, dailyClosingMapper, refundMapper,
                    payCollectMapper, payTransactionMapper);

    @BeforeEach
    void initTableInfo() {
        for (Class<?> entity : List.of(PayCollectPo.class, PayIntentPo.class, PayTransactionPo.class,
                RefundPo.class, ShiftPo.class, DailyClosingPo.class)) {
            TableInfo tableInfo = TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
            LambdaUtils.installCache(tableInfo);
        }
        when(payIntentMapper.selectList(any())).thenReturn(List.of());
        when(payTransactionMapper.selectList(any())).thenReturn(List.of());
        when(refundMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void combinedCollectionKeepsEveryLegIncludingOnlineChannel() {
        when(payCollectMapper.selectList(any())).thenReturn(List.of(
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
        when(payCollectMapper.selectList(any())).thenReturn(List.of(
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
        when(payCollectMapper.selectList(any())).thenReturn(List.of(
                collect(1L, "PC-OK", "USD", "{\"payable\":800}",
                        "{\"collectedByMethod\":[{\"method\":\"CASH\",\"amount\":800}]}")));

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertEquals(1, view.collections().size());
        assertEquals(800L, view.totalCollected());
        // 只读条件里必须带 state=CONFIRMED（INIT/HOLD/FAILED 的尝试不算收款）。
        LambdaQueryWrapper<PayCollectPo> wrapper = capturedCollectWrapper();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("state"), "必须按 state 过滤，否则失败尝试会被当成收款: " + sql);
    }

    @Test
    void tenantAndOrderScopeArePushedIntoTheQuery() {
        when(payCollectMapper.selectList(any())).thenReturn(List.of());

        service.orderCollections(TENANT_ID, List.of(ORDER_ID, 70L));

        String sql = capturedCollectWrapper().getSqlSegment();
        assertTrue(sql.contains("tenant_id"), "读资金明细必须显式带租户条件: " + sql);
        assertTrue(sql.contains("order_id"), "必须按订单过滤，禁止全表扫描: " + sql);
    }

    @Test
    void unparseableSnapshotDoesNotBreakTheWholePage() {
        when(payCollectMapper.selectList(any())).thenReturn(List.of(
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
        when(payCollectMapper.selectList(any())).thenReturn(List.of(
                collect(1L, "PC-1", "USD", "{\"payable\":900}",
                        "{\"collectedByMethod\":[{\"method\":\"CASH\",\"amount\":900},"
                                + "{\"method\":\"POINT\",\"amount\":0}]}")));

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertEquals(1, view.collections().get(0).legs().size(), "零金额分腿不是一次实际支付");
        assertFalse(view.collections().get(0).combined());
    }

    @Test
    void mixedCurrencyIsFlaggedAndNoSingleCurrencyIsGiven() {
        when(payCollectMapper.selectList(any())).thenReturn(List.of(
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
        PayIntentPo intent = new PayIntentPo();
        intent.setId(31L);
        intent.setTenantId(TENANT_ID);
        intent.setOrderId(ORDER_ID);
        intent.setProvider("ALIPAY");
        intent.setPaymentMethod("ALIPAY");
        intent.setAmount(new BigDecimal("2000"));
        intent.setCurrencyCode("CNY");
        intent.setStatus("SUCCEEDED");
        intent.setCreatedAt(LocalDateTime.of(2026, 9, 19, 12, 0));
        when(payIntentMapper.selectList(any())).thenReturn(List.of(intent));

        PayTransactionPo tx = new PayTransactionPo();
        tx.setId(88L);
        tx.setTenantId(TENANT_ID);
        tx.setPaymentIntentId(31L);
        tx.setProvider("ALIPAY");
        tx.setProviderTransactionNo("2026091922001400000001");
        tx.setAmount(new BigDecimal("2000"));
        tx.setCurrencyCode("CNY");
        tx.setStatus("SUCCEEDED");
        tx.setOccurredAt(LocalDateTime.of(2026, 9, 19, 12, 0, 1));
        when(payTransactionMapper.selectList(any())).thenReturn(List.of(tx));
        when(payCollectMapper.selectList(any())).thenReturn(List.of());

        OrderCollectionsDto view = service.orderCollections(TENANT_ID, List.of(ORDER_ID)).get(0);

        assertEquals(1, view.transactions().size());
        assertEquals("ALIPAY", view.transactions().get(0).provider());
        assertEquals("2026091922001400000001", view.transactions().get(0).providerTransactionNo(),
                "线上渠道交易号是对账凭据，不能丢");
        assertEquals(2000L, view.transactions().get(0).amount());
    }

    @Test
    void refundsAreListedAndOnlyRefundedMoneyCountsAsRefunded() {
        when(payCollectMapper.selectList(any())).thenReturn(List.of());
        when(refundMapper.selectList(any())).thenReturn(List.of(
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
        when(payCollectMapper.selectList(any())).thenReturn(List.of());

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

    private PayCollectPo collect(Long id, String collectNo, String currency, String requestJson, String responseJson) {
        PayCollectPo po = new PayCollectPo();
        po.setId(id);
        po.setTenantId(TENANT_ID);
        po.setCollectNo(collectNo);
        po.setOrderId(ORDER_ID);
        po.setState("CONFIRMED");
        po.setCurrencyCode(currency);
        po.setRequestJson(requestJson);
        po.setResponseJson(responseJson);
        po.setCreatedAt(LocalDateTime.of(2026, 9, 19, 12, 0).plusMinutes(id));
        return po;
    }

    private RefundPo refund(Long id, String status, String requested, String approved) {
        RefundPo po = new RefundPo();
        po.setId(id);
        po.setTenantId(TENANT_ID);
        po.setOrderId(ORDER_ID);
        po.setStatus(status);
        po.setRequestedAmount(new BigDecimal(requested));
        po.setApprovedAmount(approved == null ? null : new BigDecimal(approved));
        po.setCurrencyCode("CNY");
        po.setCreatedAt(LocalDateTime.of(2026, 9, 19, 13, 0).plusMinutes(id));
        return po;
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<PayCollectPo> capturedCollectWrapper() {
        org.mockito.ArgumentCaptor<LambdaQueryWrapper<PayCollectPo>> captor =
                org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(payCollectMapper, org.mockito.Mockito.atLeastOnce()).selectList(captor.capture());
        return captor.getValue();
    }
}
