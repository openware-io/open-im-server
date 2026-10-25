package io.openware.common.payment.application;

import io.openware.common.payment.domain.channel.model.ChannelConfig;
import io.openware.common.payment.domain.channel.repository.ChannelConfigRepository;
import io.openware.common.payment.domain.channel.repository.Persisted;
import io.openware.common.payment.domain.channel.service.ChannelConfigResolver;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 线上支付渠道配置（支付宝/微信/Stripe，默认关）。
 * 线上渠道需显式开通后才能在组合收款中使用；现金/储值币/积分为线下默认可用。
 *
 * <p>作用域解析（门店覆盖 &gt; 业态默认 &gt; 租户默认）在
 * {@link ChannelConfigResolver}；本服务只做编排与审计。
 */
@Service
public class ChannelApplicationService {
    public static final Set<String> ONLINE_CHANNELS = Set.of("ALIPAY", "WECHAT", "STRIPE");

    private final ChannelConfigRepository channelConfigRepository;
    private final AuditClient auditClient;

    @Autowired
    public ChannelApplicationService(ChannelConfigRepository channelConfigRepository, AuditClient auditClient) {
        this.channelConfigRepository = channelConfigRepository;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配/单测：不传审计客户端时使用关闭态客户端（生产装配始终注入真实客户端）。 */
    public ChannelApplicationService(ChannelConfigRepository channelConfigRepository) {
        this(channelConfigRepository, AuditClient.disabled());
    }

    public List<ChannelConfigDto> list(Long tenantId) {
        return list(tenantId, null, null);
    }

    /** 门店读取按门店覆盖 > 业态默认 > 租户默认解析。 */
    public List<ChannelConfigDto> list(Long tenantId, Long storeId, String requestedBusinessType) {
        String businessType = normalize(requestedBusinessType);
        List<Persisted<ChannelConfig>> rows = channelConfigRepository.listActiveByTenant(tenantId);
        if (storeId == null) {
            List<Persisted<ChannelConfig>> scoped = businessType == null
                    ? rows
                    : rows.stream().filter(row -> row.aggregate().hasBusinessType(businessType)).toList();
            return scoped.stream().map(ChannelConfigDto::from).toList();
        }
        final String scopedBusinessType = businessType;
        final Long scopedStoreId = storeId;
        final List<ChannelConfig> candidates = rows.stream().map(Persisted::aggregate).toList();
        final Map<Long, Persisted<ChannelConfig>> byId = rows.stream()
                .collect(Collectors.toMap(row -> row.aggregate().id(), row -> row));
        // 指定门店时逐个线上渠道解析出唯一生效配置；未配置的渠道不出现在列表里（与既有行为一致）。
        return ONLINE_CHANNELS.stream()
                .map(channel -> ChannelConfigResolver.resolve(candidates, channel, scopedStoreId,
                        scopedBusinessType))
                .flatMap(java.util.Optional::stream)
                .map(config -> ChannelConfigDto.from(byId.get(config.id()))).toList();
    }

    /** 开通/关闭线上渠道（按租户或门店粒度覆盖）。 */
    @Transactional
    public ChannelConfigDto setEnabled(Long tenantId, Long storeId, String channel, boolean enabled, String merchantId) {
        return setEnabled(tenantId, storeId, null, channel, enabled, merchantId, null, null);
    }

    /**
     * 批量开通/关闭：先整批校验门店范围与渠道，再逐店写入；整批共用同一个事务。
     *
     * <p>校验与编排必须在应用服务内完成（standards/10 §应用服务规则）：
     * 控制器只做协议转换，不持有 `@Transactional`。任何一条非法都会在写入前抛出，
     * 不会留下“前半批落库、后半批失败”的半截配置。
     */
    @Transactional
    public List<ChannelConfigDto> setEnabledBatch(Long tenantId, String businessType, String merchantId,
                                                  List<Long> storeIds, List<ChannelToggleCommand> config,
                                                  String idempotencyKey) {
        if (config == null || config.isEmpty() || storeIds == null || storeIds.isEmpty()
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("PAYMENT_CHANNEL_BATCH_INVALID");
        }
        // 先整批校验门店与渠道，再逐店写入：校验失败时一行都不写。
        Set<Long> uniqueStores = new HashSet<>();
        for (Long storeId : storeIds) {
            if (storeId == null || storeId <= 0 || !uniqueStores.add(storeId)) {
                throw new IllegalArgumentException("STORE_SCOPE_FORBIDDEN");
            }
        }
        for (ChannelToggleCommand toggle : config) {
            if (toggle == null || toggle.channel() == null || !ONLINE_CHANNELS.contains(toggle.channel())) {
                throw new IllegalArgumentException("CHANNEL_NOT_ONLINE");
            }
        }
        List<ChannelConfigDto> result = new ArrayList<>();
        for (Long storeId : storeIds) {
            for (ChannelToggleCommand toggle : config) {
                result.add(setEnabled(tenantId, storeId, businessType, toggle.channel(), toggle.enabled(),
                        merchantId, toggle.version(),
                        idempotencyKey + "-" + storeId + "-" + toggle.channel()));
            }
        }
        return result;
    }

    @Transactional
    public ChannelConfigDto setEnabled(Long tenantId, Long storeId, String businessType, String channel,
                                       boolean enabled, String merchantId, Integer expectedVersion, String idempotencyKey) {
        // 非线上渠道是无状态前置校验：不为它写失败痕迹，只覆盖写操作的执行结果。
        if (!ONLINE_CHANNELS.contains(channel)) throw new IllegalStateException("CHANNEL_NOT_ONLINE");
        try {
            String scopeType = normalize(businessType);
            if (storeId != null && scopeType == null) {
                var context = TenantContextHolder.get();
                scopeType = context == null ? null : normalize(context.businessType());
            }
            String storedType = scopeType == null ? ChannelConfig.ANY_BUSINESS_TYPE : scopeType;
            Persisted<ChannelConfig> existingRow = channelConfigRepository
                    .findByScope(tenantId, storeId, storedType, channel).orElse(null);
            ChannelConfig existing = existingRow == null ? null : existingRow.aggregate();
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Persisted<ChannelConfig> idem = channelConfigRepository
                        .findByIdempotencyKey(tenantId, channel, idempotencyKey).orElse(null);
                if (idem != null) return ChannelConfigDto.from(idem);
            }
            if (existing != null && expectedVersion != null && !expectedVersion.equals(existing.version())) {
                throw new IllegalStateException("VERSION_CONFLICT");
            }
            LocalDateTime now = LocalDateTime.now();
            ChannelConfig draft = new ChannelConfig(existing == null ? null : existing.id(), tenantId, storeId,
                    storedType, channel, enabled, merchantId,
                    existing == null ? ChannelConfig.STATUS_ACTIVE : existing.status(),
                    existing == null ? 0 : existing.version(), idempotencyKey);
            Persisted<ChannelConfig> saved = existing == null
                    ? channelConfigRepository.insert(draft, now)
                    : updateOrConflict(draft, existing.version(), now);
            // 渠道开通/关闭决定该租户能否走线上收款：此前成功/失败都没有留痕。
            // detail 只放租户/门店/渠道与开关值，**不含** merchantId 等商户参数。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .storeId(storeId)
                    .action("payment.channel.toggle")
                    .actionLabel("支付渠道开通/关闭")
                    .resourceType("pay_channel_config")
                    .resourceId(saved.aggregate().id() == null ? null : String.valueOf(saved.aggregate().id()))
                    .resourceName(channel)
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"channel\":" + jsonText(channel) + ",\"storeId\":" + storeId
                            + ",\"enabled\":" + enabled + "}")
                    .build());
            return ChannelConfigDto.from(saved);
        } catch (RuntimeException failure) {
            // 失败留痕：动作码与成功路径同码，result=FAILURE + 稳定 errorCode（只 WARN，异常原样抛出）。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .storeId(storeId)
                    .action("payment.channel.toggle")
                    .resourceType("pay_channel_config")
                    .resourceName(channel)
                    .result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure))
                    .detailJson("{\"channel\":" + jsonText(channel) + ",\"storeId\":" + storeId + "}")
                    .build());
            throw failure;
        }
    }

    /**
     * CAS 更新；受影响 0 行即并发冲突。
     *
     * <p>上一步的版本比对只是快速失败（给出可读的 409），**真正的并发判定在这条带版本条件的 UPDATE 里**：
     * 先读出来比对再无条件写回，会让两个并发请求双双通过比对。
     */
    private Persisted<ChannelConfig> updateOrConflict(ChannelConfig draft, int expectedVersion,
                                                      LocalDateTime occurredAt) {
        return channelConfigRepository.updateWithVersion(draft, expectedVersion, occurredAt)
                .orElseThrow(() -> new IllegalStateException("VERSION_CONFLICT"));
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
        return channelConfigRepository.existsEnabled(tenantId, storeId, channel);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /** 批量写入的单渠道开关命令（应用层类型，避免控制器 DTO 渗透到服务）。 */
    public record ChannelToggleCommand(String channel, boolean enabled, Integer version) {}
}
