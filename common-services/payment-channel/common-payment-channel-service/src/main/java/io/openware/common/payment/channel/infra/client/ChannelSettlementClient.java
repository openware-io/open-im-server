package io.openware.common.payment.channel.infra.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceSignature;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * payment-service 内部结算接口客户端（渠道回调 → 支付/订单落账）。
 *
 * <p>鉴权口径与全仓内部服务一致：v2 canonical HMAC，出站签名复用 SDK 的
 * {@link InternalServiceSignature}（与 payment-service 的
 * {@code InternalServiceAuthenticationFilter} 服务端校验同一实现），headers：
 * {@code X-IM-Service-Auth-Version: 2}、{@code X-IM-Service-Source}、{@code X-IM-Service-Request-Id}、
 * {@code X-IM-Service-Content-SHA256}、{@code X-IM-Service-Timestamp}、{@code X-IM-Service-Signature}。
 *
 * <p>失败一律抛 {@link ChannelSettlementException}，绝不返回"假装成功"：调用方必须让渠道重试，
 * 否则渠道侧已扣款、本侧无资金事实就是静默丢钱。
 */
@Component
@Slf4j
public class ChannelSettlementClient {

    /** payment-service 内部结算端点（/internal/payment/** 由内部 HMAC filter 保护）。 */
    public static final String SETTLEMENT_PATH = "/internal/payment/channel-settlement";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String serviceName;
    private final String secret;

    @Autowired
    public ChannelSettlementClient(
            @Value("${app.payment-settlement.base-url:http://common-payment-service:4140}") String baseUrl,
            @Value("${internal.service-auth.service-name:common-payment-channel-service}") String serviceName,
            @Value("${internal.service-auth.secret}") String secret) {
        this(RestClient.builder().baseUrl(baseUrl).build(), serviceName, secret);
    }

    /** 测试用构造器：注入已构造好的 RestClient（MockRestServiceServer 可绑定）。 */
    ChannelSettlementClient(RestClient restClient, String serviceName, String secret) {
        this.restClient = restClient;
        this.serviceName = serviceName;
        this.secret = secret;
    }

    /**
     * 调用内部结算接口。
     *
     * @return 服务端结算结果（含 applied/replayed 与订单状态快照）
     * @throws ChannelSettlementException 签名配置缺失、请求序列化失败、服务不可达或返回非 2xx
     */
    public ChannelSettlementResponse settle(ChannelSettlementRequest request) {
        if (serviceName == null || serviceName.isBlank() || secret == null || secret.isBlank()) {
            // 不静默：缺少内部服务签名配置时宁可让渠道重试，也不能发一个注定 401 的请求后当成功。
            throw new ChannelSettlementException("缺少内部服务签名配置: internal.service-auth.service-name/secret");
        }
        byte[] body = serialize(request);
        long timestamp = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String contentHash = InternalServiceSignature.contentHash(body);
        String signature = InternalServiceSignature.sign(secret, "POST", SETTLEMENT_PATH, null,
                MediaType.APPLICATION_JSON_VALUE, contentHash, serviceName, requestId, timestamp);
        try {
            ChannelSettlementResponse response = restClient.post()
                    .uri(SETTLEMENT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(InternalServiceAuthentication.SOURCE_HEADER, serviceName)
                    .header(InternalServiceAuthentication.VERSION_HEADER,
                            InternalServiceAuthentication.AUTHENTICATION_VERSION)
                    .header(InternalServiceAuthentication.REQUEST_ID_HEADER, requestId)
                    .header(InternalServiceAuthentication.CONTENT_SHA256_HEADER, contentHash)
                    .header(InternalServiceAuthentication.TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(InternalServiceAuthentication.SIGNATURE_HEADER, signature)
                    .body(body)
                    .retrieve()
                    .body(ChannelSettlementResponse.class);
            if (response == null) {
                throw new ChannelSettlementException("渠道结算接口返回空响应体");
            }
            return response;
        } catch (RestClientResponseException non2xx) {
            throw new ChannelSettlementException("渠道结算接口返回非 2xx: status="
                    + non2xx.getStatusCode().value(), non2xx);
        } catch (ResourceAccessException unreachable) {
            throw new ChannelSettlementException("渠道结算接口不可达: " + unreachable.getMessage(), unreachable);
        }
    }

    private byte[] serialize(ChannelSettlementRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            log.error("渠道结算请求序列化失败: provider={} transactionId={}",
                    request.provider(), request.transactionId(), e);
            throw new ChannelSettlementException("渠道结算请求序列化失败", e);
        }
    }
}
