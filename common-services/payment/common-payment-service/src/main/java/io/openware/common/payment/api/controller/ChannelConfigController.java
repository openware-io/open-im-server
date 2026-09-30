package io.openware.common.payment.api.controller;

import io.openware.common.payment.application.ChannelApplicationService;
import io.openware.common.payment.application.ChannelConfigDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 线上支付渠道配置（默认关）。 */
@RestController
@RequestMapping("/admin/payment-channels")
public class ChannelConfigController {
    private final ChannelApplicationService channelService;

    public ChannelConfigController(ChannelApplicationService channelService) { this.channelService = channelService; }

    @GetMapping
    public List<ChannelConfigDto> list(@RequestParam Long tenantId, @RequestParam(required = false) Long storeId,
                                       @RequestParam(required = false) String businessType) {
        return channelService.list(tenantId, storeId, businessType);
    }

    @PostMapping
    public ChannelConfigDto setEnabled(@RequestBody SetChannelRequest req) {
        return channelService.setEnabled(req.tenantId(), req.storeId(), req.businessType(), req.channel(), req.enabled(),
                req.merchantId(), req.version(), req.idempotencyKey());
    }

    public record SetChannelRequest(Long tenantId, Long storeId, String businessType, String channel, boolean enabled,
                                    String merchantId, Integer version, String idempotencyKey) {}
}
