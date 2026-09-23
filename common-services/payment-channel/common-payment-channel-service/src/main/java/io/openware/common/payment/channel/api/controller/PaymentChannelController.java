package io.openware.common.payment.channel.api.controller;

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

    public PaymentChannelController(ChannelProviderApplicationService channelService) {
        this.channelService = channelService;
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

    @PostMapping("/{provider}/callback")
    public ResponseEntity<String> callback(@PathVariable String provider,
                                           @RequestBody(required = false) String rawBody,
                                           @RequestHeader Map<String, String> headers) {
        VerifyCallbackResult result = channelService.verifyCallback(provider, new VerifyCallbackRequest(rawBody, headers));
        CallbackAck ack = channelService.callbackAck(provider, result);
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
