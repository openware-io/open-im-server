package io.openware.common.payment.channel.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 支付渠道配置（微信/支付宝/Stripe），密钥全部来自 application.yml 环境占位，禁止硬编码真实密钥。
 * enabled=false 时渠道能力关闭，下单/验签/查单入口被应用服务拒绝（PAYMENT_CHANNEL_DISABLED）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "pay-channel-provider")
public class PayChannelProviderProperties {

    private Wechat wechat = new Wechat();
    private Alipay alipay = new Alipay();
    private Stripe stripe = new Stripe();
    private Sandbox sandbox = new Sandbox();

    /** 微信支付配置：apiVersion=v2（XML 统一下单 + MD5/HMAC-SHA256）或 v3（JSON + RSA + AES-256-GCM）。 */
    @Getter
    @Setter
    public static class Wechat {
        private boolean enabled;
        /** v2 | v3，默认 v2；v3 使用 JSON API 与平台证书验签。 */
        private String apiVersion = "v2";
        private String appId;
        private String merchantId;
        /** v2 API 密钥（sign MD5/HMAC-SHA256）。 */
        private String secretKey;
        private String notifyUrl;
        /** v3 APIv3 密钥（32 字节，AES-256-GCM 解密回调 resource）。 */
        private String apiV3Key;
        /** v3 商户 API 私钥（PKCS#8 PEM）。 */
        private String merchantPrivateKey;
        /** v3 商户 API 证书序列号。 */
        private String merchantSerialNo;
        /** v3 平台证书公钥（X.509 PEM，验签回调）。 */
        private String platformPublicKey;
        /** v3 平台证书序列号（可选，配置后校验 Wechatpay-Serial 头）。 */
        private String platformSerialNo;
    }

    /** 支付宝配置：RSA2 签名（merchantPrivateKey 签名 / alipayPublicKey 验签）。 */
    @Getter
    @Setter
    public static class Alipay {
        private boolean enabled;
        private String appId;
        private String merchantPrivateKey;
        private String alipayPublicKey;
        private String notifyUrl;
        private String gatewayUrl = "https://openapi.alipay.com/gateway.do";
    }

    /** Stripe 配置：secretKey Bearer 认证，webhookSecret 验签（Stripe-Signature）。 */
    @Getter
    @Setter
    public static class Stripe {
        private boolean enabled;
        private String secretKey;
        private String webhookSecret;
        private String apiUrl = "https://api.stripe.com/v1";
        /** Stripe-Signature 时间戳容忍窗口（秒），防旧签名重放。 */
        private long webhookToleranceSeconds = 300;
    }

    /**
     * SANDBOX 渠道配置：**仅供本地 Kind E2E**（默认关闭，禁止在共享/生产环境启用）。
     *
     * <p>本地 Kind 没有真实 Alipay/Wechat/Stripe 商户密钥，真实渠道回调无法在 E2E 中产生资金事实。
     * SANDBOX 用固定 HMAC 密钥对回调 JSON 验签，走与真实渠道**完全相同**的
     * {@code callback → 渠道交易落库 → payment-service 内部结算 → pay_intent/ord_order} 链路，
     * 因此成功/失败/重复/退款回调都能在真实部署上验证，而不是只验证签失败。
     */
    @Getter
    @Setter
    public static class Sandbox {
        private boolean enabled;
        /** 回调验签密钥（本地固定测试值；生产环境不应启用 SANDBOX）。 */
        private String webhookSecret;
    }
}
