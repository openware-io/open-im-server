package io.openware.common.payment.api.controller;

import io.openware.common.payment.application.ChannelApplicationService;
import io.openware.common.payment.application.ChannelConfigDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

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

    @PostMapping("/batch")
    @org.springframework.transaction.annotation.Transactional
    public List<ChannelConfigDto> setEnabledBatch(@RequestBody BatchChannelRequest req) {
        if (req == null || req.config() == null || req.config().isEmpty() || req.storeIds() == null || req.storeIds().isEmpty()
                || req.idempotencyKey() == null || req.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("PAYMENT_CHANNEL_BATCH_INVALID");
        }
        Set<Long> uniqueStores = new HashSet<>();
        List<ChannelConfigDto> result = new java.util.ArrayList<>();
        for (Long storeId : req.storeIds()) {
            if (storeId == null || storeId <= 0 || !uniqueStores.add(storeId)) throw new IllegalArgumentException("STORE_SCOPE_FORBIDDEN");
            for (ChannelToggle toggle : req.config()) {
                if (toggle == null || toggle.channel() == null || !ChannelApplicationService.ONLINE_CHANNELS.contains(toggle.channel())) {
                    throw new IllegalArgumentException("CHANNEL_NOT_ONLINE");
                }
                result.add(channelService.setEnabled(req.tenantId(), storeId, req.businessType(), toggle.channel(),
                        toggle.enabled(), req.merchantId(), toggle.version(), req.idempotencyKey() + "-" + storeId + "-" + toggle.channel()));
            }
        }
        return result;
    }

    public record SetChannelRequest(Long tenantId, Long storeId, String businessType, String channel, boolean enabled,
                                    String merchantId, Integer version, String idempotencyKey) {}

    public record BatchChannelRequest(Long tenantId, String businessType, String merchantId,
                                      List<Long> storeIds, List<ChannelToggle> config, String idempotencyKey) {}
    public record ChannelToggle(String channel, boolean enabled, Integer version) {}
}
