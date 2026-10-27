package io.openware.common.payment.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.application.ChannelSettlementApplicationService;
import io.openware.common.payment.application.ChannelSettlementCommand;
import io.openware.common.payment.application.ChannelSettlementResult;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 渠道回调用内部结算接口（Gateway 不外暴露 {@code /internal/**}）。
 *
 * <p>调用方是 {@code common-payment-channel-service}：它验签成功后才调用本接口；内部鉴权由 SDK 的
 * v2 canonical HMAC filter 完成（{@code /internal/payment/**} 前缀），本控制器再断言 filter 已认证，
 * 避免路由前缀调整后出现"未鉴权也能结算"的静默缺口。
 *
 * <p>语义：
 * <ul>
 *   <li>成功收款回调 → 200，{@code applied=true}，原子落 pay_intent + pay_transaction + 订单收款；</li>
 *   <li>重复回调 → 200，{@code replayed=true}，不重复记账；</li>
 *   <li>失败/关闭回调 → 200，{@code applied=false}，不改订单收款；</li>
 *   <li>金额/币种/租户/门店不一致 → 422；订单已被其他收款改写 → 409；订单不存在 → 404。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/payment")
public class InternalChannelSettlementController {

    private final ChannelSettlementApplicationService settlementService;
    private final HttpServletRequest request;

    public InternalChannelSettlementController(ChannelSettlementApplicationService settlementService,
                                               HttpServletRequest request) {
        this.settlementService = settlementService;
        this.request = request;
    }

    /** 渠道回调幂等结算：由 payment-channel-service 在验签成功后调用。 */
    @PostMapping("/channel-settlement")
    public ChannelSettlementResult settle(@RequestBody ChannelSettlementCommand command) {
        verifyInternalAuth();
        return settlementService.settle(command);
    }

    private void verifyInternalAuth() {
        if (!Boolean.TRUE.equals(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
        }
    }
}
