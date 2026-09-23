package io.openware.platform.order.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 读租户域的**KTV 营业时间**（内部只读 HTTP）：{@code tnt_tenant_config.ktv_business_hours}，
 * 门店级覆盖租户默认，缺省 <b>18:00 – 次日 05:00</b>（KTV 夜间业态）。order 域不直连租户库。
 *
 * <p><b>失败语义：读不到就跳过校验（放行）+ WARN</b>。营业时间是「限制客人可下单时段」的规则，
 * 租户服务不可达时若按缺省窗口硬拦，就会把实际营业时间不同的门店（例如午市门店）挡在门外；
 * 相比之下「这次没校验到」是可接受的降级，营业本身不受影响。缺配置行（null）不属于失败：
 * 服务端已经给出缺省窗口，按缺省校验。
 */
@Slf4j
@Component
public class TenantBusinessHoursClient {
    private static final String SERVICE_NAME = "platform-order-service";
    private static final String SOURCE_HEADER = "X-IM-Service-Source";
    private static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-IM-Service-Signature";

    /** 缺省营业时间（与租户域 KtvBusinessHoursApplicationService 的缺省一致）。 */
    public static final LocalTime DEFAULT_OPEN = LocalTime.of(18, 0);
    public static final LocalTime DEFAULT_CLOSE = LocalTime.of(5, 0);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String internalSecret;

    public TenantBusinessHoursClient(
            @Value("${app.tenant-service.base-url:http://platform-tenant-service:4110}") String baseUrl,
            @Value("${app.internal-auth.secret:open-im-internal-dev-secret}") String internalSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalSecret = internalSecret;
    }

    /**
     * 门店生效营业时间；租户服务不可达 / 响应非法时返回 {@code empty}（调用方按「本次不校验」处理）。
     */
    public Optional<BusinessHours> resolve(Long storeId) {
        String token = TenantContextHolder.tokenOrNull();
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/internal/tenant/business-hours");
                        if (storeId != null) {
                            builder.queryParam("storeId", storeId);
                        }
                        return builder.build();
                    })
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            String open = node.path("openTime").asText(null);
            String close = node.path("closeTime").asText(null);
            if (open == null || close == null) {
                return Optional.empty();
            }
            return Optional.of(new BusinessHours(LocalTime.parse(open), LocalTime.parse(close),
                    node.path("source").asText("TENANT")));
        } catch (DateTimeParseException invalid) {
            log.warn("营业时间响应时间格式非法，跳过校验: storeId={}, cause={}", storeId, invalid.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("租户服务不可达，本次跳过营业时间校验（不影响营业）: storeId={}", storeId, e);
            return Optional.empty();
        }
    }

    /** 缺省营业时间（客户端读路径在没有上下文时用它，保证选择器仍受约束）。 */
    public static BusinessHours defaultHours() {
        return new BusinessHours(DEFAULT_OPEN, DEFAULT_CLOSE, "DEFAULT");
    }

    /** 简单签名与其余内部客户端同口径：sha256(serviceName:timestamp:secret)。 */
    private String simpleSignature(long timestamp) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((SERVICE_NAME + ":" + timestamp + ":" + internalSecret)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成内部服务签名", e);
        }
    }

    /**
     * 营业时间（左闭右开 {@code [open, close)}；{@code close < open} 表示跨自然日；
     * {@code open == close} 表示全天）。
     */
    public record BusinessHours(LocalTime open, LocalTime close, String source) {

        /** 是否跨自然日（如 18:00–05:00）。 */
        public boolean crossesMidnight() {
            return close.isBefore(open);
        }

        /** 是否全天营业。 */
        public boolean allDay() {
            return close.equals(open);
        }

        /** 该时刻是否在营业时段内。 */
        public boolean contains(LocalTime time) {
            if (time == null) {
                return false;
            }
            if (allDay()) {
                return true;
            }
            if (crossesMidnight()) {
                return !time.isBefore(open) || time.isBefore(close);
            }
            return !time.isBefore(open) && time.isBefore(close);
        }

        /** 展示文案（跨自然日标注「次日」）。 */
        public String displayText() {
            if (allDay()) {
                return "全天营业";
            }
            return open + (crossesMidnight() ? " – 次日 " : " – ") + close;
        }
    }
}
