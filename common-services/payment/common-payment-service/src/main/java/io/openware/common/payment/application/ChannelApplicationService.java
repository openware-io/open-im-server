package io.openware.common.payment.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.common.payment.infra.persistence.mapper.ChannelConfigMapper;
import io.openware.common.payment.infra.persistence.po.ChannelConfigPo;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 线上支付渠道配置（支付宝/微信/Stripe，默认关）。
 * 线上渠道需显式开通后才能在组合收款中使用；现金/储值币/积分为线下默认可用。
 */
@Service
public class ChannelApplicationService {
    public static final Set<String> ONLINE_CHANNELS = Set.of("ALIPAY", "WECHAT", "STRIPE");

    private final ChannelConfigMapper channelConfigMapper;
    private final AuditClient auditClient;

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelApplicationService(ChannelConfigMapper channelConfigMapper, AuditClient auditClient) {
        this.channelConfigMapper = channelConfigMapper;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配/单测：不传审计客户端时使用关闭态客户端（生产装配始终注入真实客户端）。 */
    public ChannelApplicationService(ChannelConfigMapper channelConfigMapper) {
        this(channelConfigMapper, AuditClient.disabled());
    }

    public List<ChannelConfigDto> list(Long tenantId) {
        return channelConfigMapper.selectList(new QueryWrapper<ChannelConfigPo>().eq("tenant_id", tenantId))
                .stream().map(ChannelConfigDto::from).toList();
    }

    /** 开通/关闭线上渠道（按租户或门店粒度覆盖）。 */
    @Transactional
    public ChannelConfigDto setEnabled(Long tenantId, Long storeId, String channel, boolean enabled, String merchantId) {
        // 非线上渠道是无状态前置校验：不为它写失败痕迹，只覆盖写操作的执行结果。
        if (!ONLINE_CHANNELS.contains(channel)) throw new IllegalStateException("CHANNEL_NOT_ONLINE");
        try {
            QueryWrapper<ChannelConfigPo> qw = new QueryWrapper<ChannelConfigPo>()
                    .eq("tenant_id", tenantId).eq("channel", channel);
            if (storeId == null) qw.isNull("store_id"); else qw.eq("store_id", storeId);
            ChannelConfigPo po = channelConfigMapper.selectOne(qw);
            if (po == null) {
                po = new ChannelConfigPo();
                po.setTenantId(tenantId); po.setStoreId(storeId); po.setChannel(channel);
                po.setStatus("ACTIVE"); po.setCreatedAt(LocalDateTime.now()); po.setUpdatedAt(LocalDateTime.now());
            }
            po.setEnabled(enabled ? 1 : 0);
            po.setMerchantId(merchantId);
            po.setUpdatedAt(LocalDateTime.now());
            if (po.getId() == null) channelConfigMapper.insert(po); else channelConfigMapper.updateById(po);
            // 渠道开通/关闭决定该租户能否走线上收款：此前成功/失败都没有留痕。
            // detail 只放租户/门店/渠道与开关值，**不含** merchantId 等商户参数。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .storeId(storeId)
                    .action("payment-channel.toggle")
                    .actionLabel("支付渠道开通/关闭")
                    .resourceType("pay_channel_config")
                    .resourceId(po.getId() == null ? null : String.valueOf(po.getId()))
                    .resourceName(channel)
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"channel\":" + jsonText(channel) + ",\"storeId\":" + storeId
                            + ",\"enabled\":" + enabled + "}")
                    .build());
            return ChannelConfigDto.from(po);
        } catch (RuntimeException failure) {
            // 失败留痕：动作码与成功路径同码，result=FAILURE + 稳定 errorCode（只 WARN，异常原样抛出）。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .storeId(storeId)
                    .action("payment-channel.toggle")
                    .resourceType("pay_channel_config")
                    .resourceName(channel)
                    .result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure))
                    .detailJson("{\"channel\":" + jsonText(channel) + ",\"storeId\":" + storeId + "}")
                    .build());
            throw failure;
        }
    }

    /** 最小 JSON 字符串转义（渠道代码未转义会拼出非法 JSON 丢审计）。 */
    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 线上渠道是否已对某租户/门店开通。 */
    public boolean isOnlineEnabled(Long tenantId, Long storeId, String channel) {
        if (!ONLINE_CHANNELS.contains(channel)) return true;
        QueryWrapper<ChannelConfigPo> qw = new QueryWrapper<ChannelConfigPo>()
                .eq("tenant_id", tenantId).eq("channel", channel).eq("enabled", 1).eq("status", "ACTIVE");
        qw.and(w -> w.isNull("store_id").or().eq("store_id", storeId));
        return channelConfigMapper.selectCount(qw) > 0;
    }
}
