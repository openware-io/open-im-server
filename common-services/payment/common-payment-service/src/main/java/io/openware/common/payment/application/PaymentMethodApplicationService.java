package io.openware.common.payment.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.payment.infra.persistence.mapper.TenantPaymentMethodMapper;
import io.openware.common.payment.infra.persistence.po.TenantPaymentMethodPo;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 支付方式可用性（统一口径，B端/C端都走这里，保证场景一致）。
 * - 目录：CASH/WALLET/POINT/ALIPAY/WECHAT/STRIPE。
 * - 租户授权（tenantAllowed）：CASH 兜底永远可用；其余需平台在 tenant_payment_method.granted=1 授权；
 *   线上渠道（ALIPAY/WECHAT/STRIPE）还需 pay_channel_config 已开通（商户参数）。
 * - 用户可见（userVisible）：tenantAllowed 且租户开关 user_enabled=1（默认开）。
 */
@Service
public class PaymentMethodApplicationService {
    public static final List<String> CATALOG = List.of("CASH", "WALLET", "POINT", "ALIPAY", "WECHAT", "STRIPE");
    private static final Set<String> ONLINE = Set.of("ALIPAY", "WECHAT", "STRIPE");

    private final TenantPaymentMethodMapper tenantPaymentMethodMapper;
    private final ChannelApplicationService channelService;
    private final AuditClient auditClient;

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentMethodApplicationService(TenantPaymentMethodMapper tenantPaymentMethodMapper,
                                           ChannelApplicationService channelService,
                                           AuditClient auditClient) {
        this.tenantPaymentMethodMapper = tenantPaymentMethodMapper;
        this.channelService = channelService;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配/单测：不传审计客户端时使用关闭态客户端（生产装配始终注入真实客户端）。 */
    public PaymentMethodApplicationService(TenantPaymentMethodMapper tenantPaymentMethodMapper,
                                           ChannelApplicationService channelService) {
        this(tenantPaymentMethodMapper, channelService, AuditClient.disabled());
    }

    /**
     * 统一可用性查询：view=admin 租户授权视角（B端收银）；view=user 用户可见视角（C端支付）。
     *
     * <p><b>币种能力（规范 §2.2.3）</b>：线上渠道受币种约束（微信/支付宝仅 CNY，Stripe 以 USD 结算）。
     * 当前租户币种不满足时，该方式带 {@code available=false + unavailableReason}，且**不可见**
     * （userVisible=false），从 C 端支付列表里直接消失，而不是让用户点进去才失败。
     */
    public List<PaymentMethodView> methods(String view) {
        TenantContext ctx = requireContext();
        boolean userView = "user".equalsIgnoreCase(view);
        String currencyCode = CurrencyResolver.currentCode();
        Map<String, TenantPaymentMethodPo> rows = rows(ctx.tenantId());
        return CATALOG.stream()
                .map(m -> {
                    boolean allowed = tenantAllowed(ctx, m, rows);
                    boolean currencySupported = PaymentChannelCurrencyCapability.isSupported(m, currencyCode);
                    boolean visible = allowed && currencySupported && (!userView || userEnabled(m, rows));
                    boolean available = allowed && currencySupported;
                    return new PaymentMethodView(m, allowed, visible, available,
                            available ? null : PaymentChannelCurrencyCapability.unavailableReason(m, currencyCode));
                })
                .toList();
    }

    /** 平台：某租户的支付方式授权/开关清单。 */
    public List<TenantPaymentMethodDto> listGrants(Long tenantId) {
        return tenantPaymentMethodMapper.selectList(new LambdaQueryWrapper<TenantPaymentMethodPo>()
                        .eq(TenantPaymentMethodPo::getTenantId, tenantId))
                .stream().map(TenantPaymentMethodDto::from).toList();
    }

    /** 平台：授权/回收某租户某支付方式。 */
    @Transactional
    public TenantPaymentMethodDto setGrant(Long tenantId, String method, boolean granted) {
        // 支付方式合法性是无状态前置校验：不为它写失败痕迹，只覆盖写操作的执行结果。
        validateMethod(method);
        try {
            TenantPaymentMethodPo po = upsert(tenantId, method);
            po.setGranted(granted ? 1 : 0);
            po.setUpdatedAt(LocalDateTime.now());
            tenantPaymentMethodMapper.updateById(po);
            // 授权结果决定该租户能否收款，属资金相关配置：此前成功/失败都没有留痕。
            recordMethodAudit("payment-method.grant", "支付方式授权", tenantId, method,
                    "\"granted\":" + granted);
            return TenantPaymentMethodDto.from(po);
        } catch (RuntimeException failure) {
            recordMethodFailure("payment-method.grant", tenantId, method, failure);
            throw failure;
        }
    }

    /** 租户：是否向用户开放（默认开）。 */
    @Transactional
    public TenantPaymentMethodDto setUserEnabled(Long tenantId, String method, boolean enabled) {
        validateMethod(method);
        try {
            TenantPaymentMethodPo po = upsert(tenantId, method);
            po.setUserEnabled(enabled ? 1 : 0);
            po.setUpdatedAt(LocalDateTime.now());
            tenantPaymentMethodMapper.updateById(po);
            // 关闭某方式会让它在 C 端支付列表消失，直接影响成单：必须可回溯。
            recordMethodAudit("payment-method.user_enabled", "支付方式用户开关", tenantId, method,
                    "\"userEnabled\":" + enabled);
            return TenantPaymentMethodDto.from(po);
        } catch (RuntimeException failure) {
            recordMethodFailure("payment-method.user_enabled", tenantId, method, failure);
            throw failure;
        }
    }

    /**
     * 支付方式授权/开关的成功留痕：detail 只放租户、方式与开关值（无金额、无商户密钥）。
     */
    private void recordMethodAudit(String action, String actionLabel, Long tenantId, String method,
                                  String flagJson) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("tenant_payment_method")
                .resourceId(tenantId == null ? null : String.valueOf(tenantId))
                .resourceName(method)
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"method\":" + jsonText(method) + "," + flagJson + "}")
                .build());
    }

    /**
     * 支付方式授权/开关的失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕）；detail 不含金额与渠道商户参数。
     */
    private void recordMethodFailure(String action, Long tenantId, String method, RuntimeException failure) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .action(action)
                .resourceType("tenant_payment_method")
                .resourceId(tenantId == null ? null : String.valueOf(tenantId))
                .resourceName(method)
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"method\":" + jsonText(method) + "}")
                .build());
    }

    /** 最小 JSON 字符串转义（方式代码未转义会拼出非法 JSON 丢审计）。 */
    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 组合收款校验用：某支付方式对租户是否可用（平台授权 + 渠道，不含租户开关）。 */
    public boolean isTenantAllowed(long tenantId, Long storeId, String method) {
        return isTenantAllowed(tenantId, storeId, method, rows(tenantId));
    }

    private boolean tenantAllowed(TenantContext ctx, String method, Map<String, TenantPaymentMethodPo> rows) {
        return isTenantAllowed(ctx.tenantId(), ctx.storeId(), method, rows);
    }

    private boolean isTenantAllowed(long tenantId, Long storeId, String method, Map<String, TenantPaymentMethodPo> rows) {
        if ("CASH".equals(method)) return true;
        if (ONLINE.contains(method)) {
            return granted(rows, method) && channelService.isOnlineEnabled(tenantId, storeId, method);
        }
        return granted(rows, method);
    }

    private boolean granted(Map<String, TenantPaymentMethodPo> rows, String method) {
        TenantPaymentMethodPo po = rows.get(method);
        return po != null && po.getGranted() != null && po.getGranted() == 1;
    }

    private boolean userEnabled(String method, Map<String, TenantPaymentMethodPo> rows) {
        TenantPaymentMethodPo po = rows.get(method);
        return po == null || po.getUserEnabled() == null || po.getUserEnabled() == 1;
    }

    private Map<String, TenantPaymentMethodPo> rows(long tenantId) {
        List<TenantPaymentMethodPo> list = tenantPaymentMethodMapper.selectList(
                new LambdaQueryWrapper<TenantPaymentMethodPo>().eq(TenantPaymentMethodPo::getTenantId, tenantId));
        return list.stream().collect(Collectors.toMap(TenantPaymentMethodPo::getMethod, Function.identity(), (a, b) -> a));
    }

    private TenantPaymentMethodPo upsert(Long tenantId, String method) {
        TenantPaymentMethodPo existing = tenantPaymentMethodMapper.selectOne(
                new LambdaQueryWrapper<TenantPaymentMethodPo>()
                        .eq(TenantPaymentMethodPo::getTenantId, tenantId)
                        .eq(TenantPaymentMethodPo::getMethod, method));
        if (existing != null) return existing;
        TenantPaymentMethodPo po = new TenantPaymentMethodPo();
        po.setTenantId(tenantId);
        po.setMethod(method);
        po.setGranted(0);
        po.setUserEnabled(1);
        po.setStatus("ACTIVE");
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        tenantPaymentMethodMapper.insert(po);
        return po;
    }

    private void validateMethod(String method) {
        if (method == null || !CATALOG.contains(method)) {
            throw new ApiException(400, "PAYMENT_METHOD_UNKNOWN", "未知支付方式: " + method);
        }
    }

    private TenantContext requireContext() {
        TenantContext ctx = TenantContextHolder.get();
        if (ctx == null) throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        return ctx;
    }

    /**
     * 支付方式可用性视图。
     *
     * @param tenantAllowed     平台是否授权该租户使用（口径不变）
     * @param userVisible       C 端是否可见（授权 + 币种能力 + 租户开关）
     * @param available         当前币种下是否真正可用（不满足渠道币种能力时为 false）
     * @param unavailableReason 不可用原因（可用时为 null）
     */
    public record PaymentMethodView(String method, boolean tenantAllowed, boolean userVisible,
                                    boolean available, String unavailableReason) {}
}
