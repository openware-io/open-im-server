package io.openware.common.payment.application;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.domain.method.model.TenantPaymentMethod;
import io.openware.common.payment.domain.method.repository.TenantPaymentMethodRepository;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 支付方式可用性（统一口径，B端/C端都走这里，保证场景一致）。
 * - 目录：CASH/WALLET/POINT/ALIPAY/WECHAT/STRIPE。
 * - 租户授权（tenantAllowed）：CASH 兜底永远可用；WALLET/POINT 为租户基础能力，无记录时默认开放；线上渠道仍需显式授权；
 *   线上渠道（ALIPAY/WECHAT/STRIPE）还需 pay_channel_config 已开通（商户参数）。
 * - 用户可见（userVisible）：tenantAllowed 且租户开关 user_enabled=1（默认开）。
 *
 * <p>持久化经 {@link TenantPaymentMethodRepository}，线上渠道开通判定经
 * {@link ChannelApplicationService}（其自身也已走端口）。
 */
@Service
public class PaymentMethodApplicationService {
    public static final List<String> CATALOG = List.of("CASH", "WALLET", "POINT", "ALIPAY", "WECHAT", "STRIPE");
    private static final Set<String> ONLINE = Set.of("ALIPAY", "WECHAT", "STRIPE");

    private final TenantPaymentMethodRepository paymentMethodRepository;
    private final ChannelApplicationService channelService;
    private final AuditClient auditClient;

    @Autowired
    public PaymentMethodApplicationService(TenantPaymentMethodRepository paymentMethodRepository,
                                           ChannelApplicationService channelService,
                                           AuditClient auditClient) {
        this.paymentMethodRepository = paymentMethodRepository;
        this.channelService = channelService;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配/单测：不传审计客户端时使用关闭态客户端（生产装配始终注入真实客户端）。 */
    public PaymentMethodApplicationService(TenantPaymentMethodRepository paymentMethodRepository,
                                           ChannelApplicationService channelService) {
        this(paymentMethodRepository, channelService, AuditClient.disabled());
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
        Map<String, TenantPaymentMethod> rows = rows(ctx.tenantId());
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
        return paymentMethodRepository.listByTenant(tenantId).stream()
                .map(TenantPaymentMethodDto::from).toList();
    }

    /** 平台：授权/回收某租户某支付方式。 */
    @Transactional
    public TenantPaymentMethodDto setGrant(Long tenantId, String method, boolean granted) {
        // 支付方式合法性是无状态前置校验：不为它写失败痕迹，只覆盖写操作的执行结果。
        validateMethod(method);
        try {
            TenantPaymentMethod current = upsert(tenantId, method);
            // 只改授权开关：用户偏好保持原值（见 withSwitches 的说明）。
            TenantPaymentMethod saved = persist(current.withSwitches(granted, null));
            // 授权结果决定该租户能否收款，属资金相关配置：此前成功/失败都没有留痕。
            recordMethodAudit("payment-method.grant", "支付方式授权", tenantId, method,
                    "\"granted\":" + granted);
            return TenantPaymentMethodDto.from(saved);
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
            TenantPaymentMethod current = upsert(tenantId, method);
            // 只改用户开关：授权值保持原值。
            TenantPaymentMethod saved = persist(current.withSwitches(current.authorized(), enabled));
            // 关闭某方式会让它在 C 端支付列表消失，直接影响成单：必须可回溯。
            recordMethodAudit("payment-method.user_enabled", "支付方式用户开关", tenantId, method,
                    "\"userEnabled\":" + enabled);
            return TenantPaymentMethodDto.from(saved);
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
                .resourceType("tnt_payment_method")
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
                .resourceType("tnt_payment_method")
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

    private boolean tenantAllowed(TenantContext ctx, String method, Map<String, TenantPaymentMethod> rows) {
        return isTenantAllowed(ctx.tenantId(), ctx.storeId(), method, rows);
    }

    private boolean isTenantAllowed(long tenantId, Long storeId, String method,
                                    Map<String, TenantPaymentMethod> rows) {
        if ("CASH".equals(method)) return true;
        // 储值与积分属于租户基础经营能力。没有历史授权行时按默认开放处理，只有明确写入 granted=0 才关闭。
        if ("WALLET".equals(method) || "POINT".equals(method)) return grantedByDefault(rows, method);
        if (ONLINE.contains(method)) {
            return granted(rows, method) && channelService.isOnlineEnabled(tenantId, storeId, method);
        }
        return granted(rows, method);
    }

    private boolean grantedByDefault(Map<String, TenantPaymentMethod> rows, String method) {
        TenantPaymentMethod config = rows.get(method);
        return config == null || config.granted() == null || Boolean.TRUE.equals(config.granted());
    }

    private boolean granted(Map<String, TenantPaymentMethod> rows, String method) {
        TenantPaymentMethod config = rows.get(method);
        return config != null && Boolean.TRUE.equals(config.granted());
    }

    private boolean userEnabled(String method, Map<String, TenantPaymentMethod> rows) {
        TenantPaymentMethod config = rows.get(method);
        return config == null || config.userEnabled() == null || Boolean.TRUE.equals(config.userEnabled());
    }

    private Map<String, TenantPaymentMethod> rows(long tenantId) {
        // 同一方式理论上只有一行（租户 + 方式为身份）；撞车时取第一条而不是让装配抛异常。
        return paymentMethodRepository.listByTenant(tenantId).stream()
                .collect(Collectors.toMap(TenantPaymentMethod::method, Function.identity(), (a, b) -> a));
    }

    /**
     * 取现有配置；没有则按默认值新建（授权=0 回收、用户开关=1 默认开）。
     *
     * <p>新建后立即落库再返回：`insert` 是"新增配置"本身，而调用方随后会写开关列。
     */
    private TenantPaymentMethod upsert(Long tenantId, String method) {
        return paymentMethodRepository.findByMethod(tenantId, method)
                .orElseGet(() -> paymentMethodRepository.insert(
                        TenantPaymentMethod.create(tenantId, method, false, true)));
    }

    /** 写入开关列并返回落库后的聚合。 */
    private TenantPaymentMethod persist(TenantPaymentMethod config) {
        paymentMethodRepository.updateSwitches(config);
        return config;
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
