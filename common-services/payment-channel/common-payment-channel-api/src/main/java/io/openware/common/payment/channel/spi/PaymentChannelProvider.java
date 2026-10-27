package io.openware.common.payment.channel.spi;

/**
 * 支付渠道适配 SPI：微信/支付宝/Stripe 的统一下单、验签回调与查询入口。
 * 三个实现默认 disabled，能力查询返回 enabled=false；密钥全部来自环境占位。
 */
public interface PaymentChannelProvider {

    /** 渠道标识：wechat / alipay / stripe。 */
    String provider();

    /** 渠道能力快照（默认 disabled）。 */
    ChannelCapability capability();

    /** 创建支付意图（下单），返回客户端展示所需凭证。 */
    CreatePaymentIntentResult createPaymentIntent(CreatePaymentIntentRequest request);

    /** 校验渠道回调并解析交易结果；verified=false 时不得改变任何资金事实。 */
    VerifyCallbackResult verifyCallback(VerifyCallbackRequest request);

    /** 主动查询渠道订单状态。 */
    QueryOrderResult queryOrder(QueryOrderRequest request);

    /** 回调确认报文：verified=true 让渠道停止重试，false 让渠道重试（按渠道 ack 语义）。 */
    default CallbackAck callbackAck(VerifyCallbackResult result) {
        return result.verified()
                ? CallbackAck.ok("text/plain;charset=utf-8", "success")
                : CallbackAck.fail("text/plain;charset=utf-8", "fail");
    }

    /**
     * 验签通过但**内部结算失败**时的确认报文：必须让渠道重试，不能返回成功 ack（否则资金事实静默丢失）。
     *
     * <p>复用各渠道既有的失败报文格式（支付宝 {@code fail}、微信 v2 XML {@code FAIL}、
     * 微信 v3 / Stripe 非 2xx），只是把 {@code verified} 置 false 交给 {@link #callbackAck} 生成——
     * 验签结果本身不受影响，日志会记录「验签成功、结算失败」。
     */
    default CallbackAck retryAck(VerifyCallbackResult result) {
        return callbackAck(new VerifyCallbackResult(false, result.providerTransactionNo(), result.orderId(),
                result.amount(), result.currency(), result.status(), result.rawPayload(), result.eventId(),
                result.tenantId()));
    }
}
