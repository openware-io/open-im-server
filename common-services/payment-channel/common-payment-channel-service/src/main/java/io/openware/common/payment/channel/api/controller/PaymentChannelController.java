package io.openware.common.payment.channel.api.controller;

import io.openware.common.payment.channel.application.ChannelCallbackSettlementBridge;
import io.openware.common.payment.channel.application.ChannelProviderApplicationService;
import io.openware.common.payment.channel.spi.CallbackAck;
import io.openware.common.payment.channel.spi.ChannelCapability;
import io.openware.common.payment.channel.spi.CreatePaymentIntentRequest;
import io.openware.common.payment.channel.spi.CreatePaymentIntentResult;
import io.openware.common.payment.channel.spi.QueryOrderRequest;
import io.openware.common.payment.channel.spi.QueryOrderResult;
import io.openware.common.payment.channel.spi.VerifyCallbackRequest;
import io.openware.common.payment.channel.spi.VerifyCallbackResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 支付渠道内部端点（渠道子域），路径不带 /api。 */
@RestController
@RequestMapping("/internal/channels")
public class PaymentChannelController {

    private final ChannelProviderApplicationService channelService;
    private final ChannelCallbackSettlementBridge settlementBridge;

    public PaymentChannelController(ChannelProviderApplicationService channelService,
                                    ChannelCallbackSettlementBridge settlementBridge) {
        this.channelService = channelService;
        this.settlementBridge = settlementBridge;
    }

    @GetMapping("/available")
    public List<ChannelCapability> available() {
        return channelService.available();
    }

    @PostMapping("/{provider}/create-intent")
    public CreatePaymentIntentResult createIntent(@PathVariable String provider,
                                                  @RequestBody CreatePaymentIntentRequest request) {
        return channelService.createIntent(provider, request);
    }

    /**
     * 渠道异步回调：先验签，验签通过后调用 payment-service 内部结算接口落账。
     *
     * <p>结算失败时返回该渠道的**失败 ack**（让渠道重投），绝不返回成功 ack 把资金事实静默吞掉；
     * 重复投递由 payment-service 的结算幂等台账回放。
     */
    @PostMapping("/{provider}/callback")
    public ResponseEntity<String> callback(@PathVariable String provider,
                                           @RequestBody(required = false) String rawBody,
                                           @RequestHeader Map<String, String> headers) {
        VerifyCallbackResult result = channelService.verifyCallback(provider, new VerifyCallbackRequest(rawBody, headers));
        boolean settlementAccepted = !result.verified() || settlementBridge.settle(provider, result);
        CallbackAck ack = settlementAccepted
                ? channelService.callbackAck(provider, result)
                : channelService.retryAck(provider, result);
        return ResponseEntity.status(ack.httpStatus())
                .contentType(MediaType.parseMediaType(ack.contentType()))
                .body(ack.body());
    }

    @PostMapping("/{provider}/query")
    public QueryOrderResult query(@PathVariable String provider,
                                  @RequestBody QueryOrderRequest request) {
        return channelService.query(provider, request);
    }
}
