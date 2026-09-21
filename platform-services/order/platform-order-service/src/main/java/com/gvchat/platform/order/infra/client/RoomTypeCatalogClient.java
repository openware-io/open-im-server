package com.gvchat.platform.order.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 资源域「房型字典 + 包厢列表」只读客户端（预约改为预约房型后的取值入口）。
 *
 * <p>为什么需要它：预约对象是房型（{@code res_room_type}），预约创建必须校验
 * 「房型存在、启用、属于该门店」与「该房型下至少有 1 个启用包厢」，而 order 域不直连资源库
 * （见 validate-persistence-dependency-boundaries 的域边界约定），只能读资源域已有接口：
 * <ul>
 *   <li>房型字典：{@code GET /admin/resources/types?storeId=} —— 资源域房型维护页的只读列表，
 *       含停用房型（status=DISABLED），因此能区分「房型不存在」与「房型被停用」；</li>
 *   <li>包厢列表：{@code GET /internal/resources?resourceType=KTV_ROOM&storeId=} —— 资源域对内的
 *       资源列表（含停用包厢与 status），用于统计「该房型启用包厢数」。</li>
 * </ul>
 * 两个接口都是只读、按 X-Tenant-Context 注入租户（MyBatis 租户拦截器自动过滤 tenant_id），
 * 资源域不做任何写入，本客户端也不缓存结果。
 *
 * <p><b>失败语义：写路径失败关闭（fail-closed）</b>。房型校验是预约创建的前置条件，
 * 读不到资源域时若降级放行，就会出现「房型不存在/被停用/无启用包厢」的预约被静默创建，
 * 因此这里抛 {@code RESOURCE_STATE_UNAVAILABLE}（HTTP 503），由前端按「稍后重试」处理。
 * 读路径（列表/详情展示房型名）由调用方决定是否吞掉异常，不用本客户端做降级。
 */
@Slf4j
@Component
public class RoomTypeCatalogClient {
    private static final String SERVICE_NAME = "platform-order-service";
    private static final String SOURCE_HEADER = "X-IM-Service-Source";
    private static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-IM-Service-Signature";
    private static final String TENANT_HEADER = "X-Tenant-Context";

    /** 包厢资源的 resourceType（res_resource.resource_type），与服务人员 KTV_SERVER 相对。 */
    public static final String ROOM_RESOURCE_TYPE = "KTV_ROOM";
    /** 资源/房型启用状态码（res_resource.status / res_room_type.status）。 */
    public static final String STATUS_ENABLED = "ENABLED";
    /** 房型启用状态码（res_room_type.status，与资源不同名）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String internalSecret;

    public RoomTypeCatalogClient(
            @Value("${app.resource-service.base-url:http://platform-resource-service:4120}") String baseUrl,
            @Value("${app.internal-auth.secret:gv-im-internal-dev-secret}") String internalSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalSecret = internalSecret;
    }

    /**
     * 门店房型字典（含停用房型）。资源域不可达时失败关闭，不返回空列表
     * ——否则调用方会把「读不到」误判成「房型不存在」。
     */
    public List<RoomTypeView> roomTypes(Long storeId) {
        JsonNode node = getJson("/admin/resources/types?storeId=" + storeId);
        List<RoomTypeView> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                result.add(new RoomTypeView(
                        item.path("id").isMissingNode() || item.path("id").isNull() ? null : item.path("id").asLong(),
                        textOrNull(item, "code"),
                        textOrNull(item, "name"),
                        integerOrNull(item, "capacity"),
                        longOrNull(item, "unitPrice"),
                        longOrNull(item, "serverUnitPrice"),
                        textOrNull(item, "status")));
            }
        }
        return result;
    }

    /** 门店内指定房型；不存在（或不属于该门店，租户/门店过滤后读不到）返回 empty。 */
    public Optional<RoomTypeView> roomType(Long storeId, Long roomTypeId) {
        if (roomTypeId == null) {
            return Optional.empty();
        }
        return roomTypes(storeId).stream()
                .filter(view -> roomTypeId.equals(view.id()))
                .findFirst();
    }

    /**
     * 门店包厢列表（含停用包厢，resourceType=KTV_ROOM）。资源域不可达时失败关闭，
     * 不返回空列表 —— 否则「该房型有 0 个启用包厢」与「读不到资源域」会被混为一谈。
     */
    public List<RoomView> rooms(Long storeId) {
        JsonNode node = getJson("/internal/resources?resourceType=" + ROOM_RESOURCE_TYPE + "&storeId=" + storeId);
        List<RoomView> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                result.add(new RoomView(
                        item.path("id").isMissingNode() || item.path("id").isNull() ? null : item.path("id").asLong(),
                        textOrNull(item, "name"),
                        textOrNull(item, "resourceCode"),
                        item.path("roomTypeId").isMissingNode() || item.path("roomTypeId").isNull()
                                ? null : item.path("roomTypeId").asLong(),
                        textOrNull(item, "status"),
                        integerOrNull(item, "capacity")));
            }
        }
        return result;
    }

    /**
     * 该房型在门店内的**启用**包厢数：超订软保护的容量基数
     * （同一个门店 + 同一房型 + 时段重叠的未取消预约数 ≥ 该数即拒绝，见规格 §3）。
     */
    public long enabledRoomCount(Long storeId, Long roomTypeId) {
        if (roomTypeId == null) {
            return 0L;
        }
        return rooms(storeId).stream()
                .filter(room -> roomTypeId.equals(room.roomTypeId()))
                .filter(room -> STATUS_ENABLED.equalsIgnoreCase(room.status()))
                .count();
    }

    /** 单次 GET：带内部服务签名与租户上下文；失败关闭（含资源域 5xx 与网络异常）。 */
    private JsonNode getJson(String uri) {
        String token = tenantToken();
        if (token == null) {
            log.warn("Missing tenant context token, refuse to read room catalog: uri={}", uri);
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "缺少门店上下文，无法校验房型，请重新选择门店后重试");
        }
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri(uri)
                    .header(TENANT_HEADER, token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            return resp == null || resp.isBlank() ? null : objectMapper.readTree(resp);
        } catch (RestClientResponseException e) {
            // 4xx（含房型/门店上下文不合法）说明请求本身不可信，同样不能降级放行。
            log.warn("Room catalog request rejected: uri={}, status={}", uri, e.getStatusCode().value());
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "房型服务拒绝了本次校验请求，请稍后重试");
        } catch (Exception e) {
            log.warn("Room catalog unreachable, failing closed: uri={}", uri, e);
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "房型服务暂时不可用，无法校验房型，请稍后重试");
        }
    }

    private String tenantToken() {
        String token = TenantContextHolder.tokenOrNull();
        return token == null || token.isBlank() ? null : token;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    /** 简单签名与 {@link ResourceStateClient} 同款：sha256(serviceName:timestamp:secret)。 */
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
     * 房型字典视图（res_room_type 只读快照）。
     *
     * @param id                房型ID（res_room_type.id）
     * @param code              门店内唯一房型编码（如 SMALL/VIP）
     * @param name              房型名称（预约页「选择包厢类型」的展示名）
     * @param capacity          标准容纳人数
     * @param unitPrice         房型房费单价（最小货币单位/计费单位；null 或 &lt;=0 = 未定价，回退门店价）
     * @param serverUnitPrice   房型服务单价（最小货币单位/计费单位；null 或 &lt;=0 = 回退门店价）
     * @param status            ACTIVE/DISABLED
     */
    public record RoomTypeView(Long id, String code, String name, Integer capacity, Long unitPrice,
                               Long serverUnitPrice, String status) {

        /** 房型是否启用（res_room_type.status=ACTIVE）。 */
        public boolean enabled() {
            return STATUS_ACTIVE.equalsIgnoreCase(status);
        }
    }

    /**
     * 包厢视图（res_resource 只读快照）。
     *
     * @param id           包厢资源ID
     * @param name         包厢名称
     * @param resourceCode 包厢编号
     * @param roomTypeId   所属房型（null = 未绑定房型，不参与按房型预约）
     * @param status       ENABLED/DISABLED/MAINTENANCE
     * @param capacity     座位容量
     */
    public record RoomView(Long id, String name, String resourceCode, Long roomTypeId, String status,
                           Integer capacity) {
    }
}
