package com.gvchat.common.payment.api.controller;

import com.gvchat.common.payment.application.ChannelApplicationService;
import com.gvchat.common.payment.application.ChannelConfigDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 线上支付渠道配置（默认关）。 */
@RestController
@RequestMapping("/admin/payment-channels")
public class ChannelConfigController {
    private final ChannelApplicationService channelService;

    public ChannelConfigController(ChannelApplicationService channelService) { this.channelService = channelService; }

    @GetMapping
    public List<ChannelConfigDto> list(@RequestParam Long tenantId) {
        return channelService.list(tenantId);
    }

    @PostMapping
    public ChannelConfigDto setEnabled(@RequestBody SetChannelRequest req) {
        return channelService.setEnabled(req.tenantId(), req.storeId(), req.channel(), req.enabled(), req.merchantId());
    }

    public record SetChannelRequest(Long tenantId, Long storeId, String channel, boolean enabled, String merchantId) {}
}
