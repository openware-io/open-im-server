package io.openware.platform.order.hotel.channel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 酒店 OTA 渠道鉴权配置：channel code → 渠道租户 + HMAC secret。
 *
 * <p>一期只支持「一个渠道凭证绑定一个租户」：外部请求不携带 tenant/organization/store/business，
 * 渠道凭证里的 {@code tenant-id} 是唯一租户来源，门店归属由服务端按
 * {@code htl_hotel.store_id} 解析并交 {@link io.openware.platform.order.hotel.HotelStoreScopeResolver}
 * 用门店权威数据复核。多租户渠道映射留到后续版本，不在 v1 里用请求参数冒充。
 *
 * <p>secret 不写在仓库里：本地 Kind 用环境变量注入（{@code HOTEL_CHANNEL_SANDBOX_SECRET}），
 * 未注入的渠道按「未配置」处理并直接 401，不存在关闭校验的开发分支。
 */
@Component
@ConfigurationProperties(prefix = "hotel.channel")
public class HotelChannelProperties {

    /** 渠道凭证最短 secret 长度；过短直接视为未配置，避免弱密钥上线。 */
    public static final int MINIMUM_SECRET_LENGTH = 32;

    private long maxClockSkewMs = 300_000L;

    private Map<String, Channel> channels = new LinkedHashMap<>();

    public long getMaxClockSkewMs() {
        return maxClockSkewMs;
    }

    public void setMaxClockSkewMs(long maxClockSkewMs) {
        this.maxClockSkewMs = maxClockSkewMs;
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, Channel> channels) {
        this.channels = channels == null ? new LinkedHashMap<>() : channels;
    }

    /**
     * 解析可用渠道凭证：channel code 必须存在、tenant-id 为正、secret 达到最短长度。
     * 任一条件不满足都返回空，让鉴权走 401（fail-closed），不做「宽松放行」。
     */
    public Optional<Channel> find(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            return Optional.empty();
        }
        Channel channel = channels.get(channelCode.trim());
        if (channel == null || channel.getTenantId() == null || channel.getTenantId() <= 0
                || channel.getSecret() == null
                || channel.getSecret().length() < MINIMUM_SECRET_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(channel);
    }

    /** 单个渠道凭证。 */
    public static class Channel {

        private Long tenantId;

        private String secret;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }
}
