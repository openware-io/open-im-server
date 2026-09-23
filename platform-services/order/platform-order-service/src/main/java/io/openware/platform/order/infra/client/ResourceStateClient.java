package io.openware.platform.order.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 资源域内部客户端：开台占用包厢、结台释放并置清洁中、转台换占用。
 *
 * <p>失败语义分三类：
 * - **时段冲突（409 RESOURCE_OCCUPIED）**必须让开台失败，避免两个订单同时占用同一包厢（业务冲突）；
 * - **占用登记（{@link #occupy}）失败关闭（fail-closed）**：资源服务不可达或没有门店上下文时，
 *   宁可拒绝开台也不静默放行 —— 原实现「服务不可达则降级放行」会留下「订单已开台但没有占用记录」，
 *   资源侧显示空闲、订单在计时，可被重复开台并重复计费；
 * - **读路径（{@link #room}/{@link #state}）与释放/清洁同步**仍按可用性降级返回 empty/只记录，
 *   不阻断订单列表展示与结台（占用释放由资源侧 HELD 超时兜底）。
 */
@Slf4j
@Component
public class ResourceStateClient {
    private static final String SERVICE_NAME = "platform-order-service";
    private static final String SOURCE_HEADER = "X-IM-Service-Source";
    private static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-IM-Service-Signature";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String internalSecret;

    public ResourceStateClient(@Value("${app.resource-service.base-url:http://platform-resource-service:4120}") String baseUrl,
                               @Value("${app.internal-auth.secret:open-im-internal-dev-secret}") String internalSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalSecret = internalSecret;
    }

    /**
     * 包厢快照（名称/编码/容量/房型），用于订单列表展示、开台占用、人数校验与「按房型定价」取值；读不到返回 empty。
     * 房型字段由资源服务在 /internal/resources/{id} 里读时回填（res_room_type），order 域不直连资源库。
     */
    public Optional<RoomSnapshot> room(Long resourceId) {
        if (resourceId == null) {
            return Optional.empty();
        }
        String token = tenantToken();
        if (token == null) {
            return Optional.empty();
        }
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri("/internal/resources/{id}", resourceId)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return Optional.of(toSnapshot(node));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 单包厢快照的**失败关闭**读（预约「到店分配包厢」用）。
     *
     * <p>与 {@link #room} 的区别只在失败语义：分配包厢前必须知道「包厢是否存在、属于哪个房型/门店」，
     * 读路径那种「读不到就 empty」的降级会让非法分配被静默放过，因此这里：
     * 资源域明确 404 → {@code RESOURCE_NOT_FOUND}；不可达/无租户上下文 → {@code RESOURCE_STATE_UNAVAILABLE}。
     */
    public RoomSnapshot requireRoom(Long resourceId) {
        if (resourceId == null) {
            throw new BusinessException("RESOURCE_NOT_FOUND", "缺少包厢 ID");
        }
        String token = tenantToken();
        if (token == null) {
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "缺少门店上下文，无法校验包厢，请重新选择门店后重试");
        }
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri("/internal/resources/{id}", resourceId)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            return toSnapshot(objectMapper.readTree(resp));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new BusinessException("RESOURCE_NOT_FOUND", "包厢不存在或已被删除");
            }
            log.warn("Resource service rejected room lookup, failing closed: resourceId={}, status={}",
                    resourceId, e.getStatusCode().value());
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "房态服务拒绝了本次包厢校验，请稍后重试");
        } catch (Exception e) {
            log.warn("Resource service unreachable, failing closed for room lookup: resourceId={}", resourceId, e);
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "房态服务暂时不可用，无法校验包厢，请稍后重试");
        }
    }

    /** 资源 JSON → 包厢快照（读路径与失败关闭路径共用同一解析口径）。 */
    private static RoomSnapshot toSnapshot(JsonNode node) {
        return new RoomSnapshot(
                node.path("name").asText(null),
                node.path("resourceCode").asText(null),
                node.path("capacity").isMissingNode() || node.path("capacity").isNull()
                        ? null : node.path("capacity").asInt(),
                node.path("roomTypeCode").asText(null),
                node.path("roomTypeName").asText(null),
                positiveOrNull(node.path("roomTypeUnitPrice")),
                positiveOrNull(node.path("roomTypeServerUnitPrice")),
                node.path("id").isMissingNode() || node.path("id").isNull() ? null : node.path("id").asLong(),
                node.path("roomTypeId").isMissingNode() || node.path("roomTypeId").isNull()
                        ? null : node.path("roomTypeId").asLong(),
                node.path("storeId").isMissingNode() || node.path("storeId").isNull()
                        ? null : node.path("storeId").asLong(),
                node.path("status").asText(null));
    }

    /** 房型单价：缺失或非正数一律按「未定价」处理（null），计费回退门店级单价。 */
    private static Long positiveOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        long value = node.asLong(0L);
        return value > 0 ? value : null;
    }

    /**
     * 包厢运行状态（可用性 + 原因）。资源服务不可达时返回 {@code empty}（降级放行，由占用冲突兜底）。
     */
    public Optional<RoomState> state(Long resourceId) {
        if (resourceId == null) {
            return Optional.empty();
        }
        String token = tenantToken();
        if (token == null) {
            return Optional.empty();
        }
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri("/internal/resources/{id}/state", resourceId)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return Optional.of(new RoomState(node.path("available").asBoolean(true),
                    node.path("state").asText(""), node.path("unavailableReason").asText(null),
                    node.path("name").asText(null)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 包厢运行状态的**失败关闭**读（预约「到店分配包厢」用）：拿不到房态就不允许分配，
     * 否则会把「使用中/清洁中」的包厢分配出去（与 {@link #occupy} 同一 fail-closed 口径）。
     */
    public RoomState requireState(Long resourceId) {
        if (resourceId == null) {
            throw new BusinessException("RESOURCE_NOT_FOUND", "缺少包厢 ID");
        }
        String token = tenantToken();
        if (token == null) {
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "缺少门店上下文，无法校验房态，请重新选择门店后重试");
        }
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri("/internal/resources/{id}/state", resourceId)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return new RoomState(node.path("available").asBoolean(true),
                    node.path("state").asText(""), node.path("unavailableReason").asText(null),
                    node.path("name").asText(null));
        } catch (Exception e) {
            log.warn("Resource service unreachable, failing closed for room state: resourceId={}", resourceId, e);
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "房态服务暂时不可用，无法校验包厢可用性，请稍后重试");
        }
    }

    /**
     * 占用包厢：冲突抛 {@link ResourceOccupiedException}；资源服务不可达或缺少门店上下文时
     * **失败关闭**（抛 {@link BusinessException}，不返回 empty），防止开台成功但没有占用记录。
     *
     * <p><b>holdExpiresAt 必须显式传 {@code endAt}</b>：资源侧对 HELD 占用有默认 15 分钟过期
     * （{@code OccupationApplicationService} 的 DEFAULT_HOLD_TIMEOUT_MINUTES），到点由
     * {@code HoldExpiryScheduler} 自动释放。开台占用若沿用该默认值，一场超过 15 分钟的 KTV 会被
     * 自动释放：房态显示空闲、订单仍在计时，可被重复开台/重复计费。因此这里把「过期时刻」对齐占用时段结束。
     */
    public Optional<Long> occupy(Long resourceId, String sourceType, Long sourceId,
                                 LocalDateTime startAt, LocalDateTime endAt) {
        if (resourceId == null) {
            return Optional.empty();
        }
        String token = tenantToken();
        if (token == null) {
            log.warn("Missing tenant context token, refuse to open without resource occupation: resourceId={} sourceId={}",
                    resourceId, sourceId);
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "缺少门店上下文，无法登记包厢占用，已拒绝本次开台，请重新选择门店后重试");
        }
        try {
            long timestamp = System.currentTimeMillis();
            // 用可变 Map：holdExpiresAt 允许缺省（endAt 为空时不传，由资源侧用默认值兜底）。
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("sourceType", sourceType);
            body.put("sourceId", sourceId);
            body.put("startAt", startAt.toString());
            body.put("endAt", endAt.toString());
            if (endAt != null) {
                body.put("holdExpiresAt", endAt.toString());
            }
            String resp = restClient.post()
                    .uri("/internal/resources/{id}/occupations", resourceId)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            long occupationId = node.path("id").asLong(0L);
            return occupationId > 0 ? Optional.of(occupationId) : Optional.empty();
        } catch (RestClientResponseException e) {
            // 资源服务明确拒绝（时段冲突）：必须阻断开台
            throw new ResourceOccupiedException("包厢已被占用，请选择其他包厢");
        } catch (Exception e) {
            // 失败关闭：拿不到占用登记就不允许开台，否则可重复开台/重复计费（WARN 记录降级原因）。
            log.warn("Resource service unreachable, failing closed for occupation: resourceId={} sourceType={} sourceId={}",
                    resourceId, sourceType, sourceId, e);
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "房态服务暂时不可用，无法登记包厢占用，已拒绝本次开台，请稍后重试");
        }
    }

    /** 释放占用（结台/转台换房）；失败只记录。 */
    public void release(Long occupationId) {
        postQuietly("/internal/resources/occupations/" + occupationId + "/release");
    }

    /** 取消占用（取消未开台会话）；失败只记录。 */
    public void cancel(Long occupationId) {
        postQuietly("/internal/resources/occupations/" + occupationId + "/cancel");
    }

    /** 置清洁状态：结台后 CLEANING，清洁完成 IDLE；失败只记录。 */
    public void setCleaning(Long resourceId, boolean cleaning) {
        if (resourceId == null) {
            return;
        }
        String token = tenantToken();
        if (token == null) {
            return;
        }
        try {
            long timestamp = System.currentTimeMillis();
            restClient.put()
                    .uri("/internal/resources/{id}/cleaning-status", resourceId)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(Map.of("cleaning", cleaning)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
            // 降级：清洁状态写失败不阻断结台，门店可在资源页手工置位
        }
    }

    private void postQuietly(String uri) {
        String token = tenantToken();
        if (token == null) {
            return;
        }
        try {
            long timestamp = System.currentTimeMillis();
            restClient.post()
                    .uri(uri)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
            // 降级：占用释放失败由资源侧 HELD 超时兜底
        }
    }

    private String tenantToken() {
        String token = TenantContextHolder.tokenOrNull();
        return token == null || token.isBlank() ? null : token;
    }

    /** 简单签名占位：sha256(serviceName:timestamp:secret)。 */
    private String simpleSignature(long timestamp) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((SERVICE_NAME + ":" + timestamp + ":" + internalSecret).getBytes(StandardCharsets.UTF_8));
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
     * 资源快照。
     *
     * @param name 资源名称（包厢名 / 服务人员名）
     * @param resourceCode 资源编码
     * @param capacity 容量（开台人数上限的校验依据；未设置时为 null）
     * @param roomTypeCode 房型编码（资源未设置房型时为 null）
     * @param roomTypeName 房型名称
     * @param roomTypeUnitPrice 房型房费单价（最小货币单位/计费单位；未定价时为 null）
     * @param roomTypeServerUnitPrice 房型服务人员单价（最小货币单位/计费单位；未定价时为 null）
     * @param resourceId 资源ID（预约分配包厢时回写 {@code ord_reservation.resource_id}；旧调用点为 null）
     * @param roomTypeId 资源所属房型ID（预约分配时必须与该预约的房型一致）
     * @param storeId 资源所属门店（预约分配时必须与该预约的门店一致）
     * @param status 资源启用状态 ENABLED/DISABLED/MAINTENANCE
     */
    public record RoomSnapshot(String name, String resourceCode, Integer capacity, String roomTypeCode,
                               String roomTypeName, Long roomTypeUnitPrice, Long roomTypeServerUnitPrice,
                               Long resourceId, Long roomTypeId, Long storeId, String status) {

        /**
         * 兼容既有 7 参构造（开台/服务人员等只关心名称、容量与房型单价的调用点）：
         * 归属与状态字段留 null，需要它们的新路径一律走 {@link #requireRoom}。
         */
        public RoomSnapshot(String name, String resourceCode, Integer capacity, String roomTypeCode,
                            String roomTypeName, Long roomTypeUnitPrice, Long roomTypeServerUnitPrice) {
            this(name, resourceCode, capacity, roomTypeCode, roomTypeName, roomTypeUnitPrice,
                    roomTypeServerUnitPrice, null, null, null, null);
        }
    }

    /**
     * 服务人员快照的**失败关闭**读（服务型商品 {@code ord_product.server_resource_id} 的写入校验用）。
     *
     * <p>与 {@link #requireRoom} 同一口径：商品保存前必须确知「这个资源存在、是 KTV_SERVER、属于本门店、启用中」，
     * 「读不到就放过」会让服务商品关联到包厢资源或别家门店的服务人员。因此：
     * 资源域明确 404 → {@code SERVER_RESOURCE_NOT_FOUND}；不可达/无租户上下文 → {@code SERVER_RESOURCE_UNAVAILABLE}。
     * 类型/门店/启用状态的判定留给调用方（需要给出各自的可读中文错误码）。
     */
    public ServerSnapshot requireServer(Long resourceId) {
        if (resourceId == null) {
            throw new BusinessException("SERVER_RESOURCE_REQUIRED", "缺少服务人员 ID");
        }
        String token = tenantToken();
        if (token == null) {
            throw new BusinessException("SERVER_RESOURCE_UNAVAILABLE",
                    "缺少门店上下文，无法校验服务人员，请重新选择门店后重试");
        }
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri("/internal/resources/{id}", resourceId)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            return toServerSnapshot(objectMapper.readTree(resp));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new BusinessException("SERVER_RESOURCE_NOT_FOUND", "服务人员不存在或已被删除");
            }
            log.warn("Resource service rejected server lookup, failing closed: resourceId={}, status={}",
                    resourceId, e.getStatusCode().value());
            throw new BusinessException("SERVER_RESOURCE_UNAVAILABLE",
                    "资源服务拒绝了本次服务人员校验，请稍后重试");
        } catch (Exception e) {
            log.warn("Resource service unreachable, failing closed for server lookup: resourceId={}", resourceId, e);
            throw new BusinessException("SERVER_RESOURCE_UNAVAILABLE",
                    "资源服务暂时不可用，无法校验服务人员，请稍后重试");
        }
    }

    /**
     * 门店内某类型资源的**列表快照**（商品列表回填服务人员名称用）。
     *
     * <p>与写路径的失败关闭相反：这是**展示用读路径**，资源服务不可达时返回空列表
     * （名称降级为 null），绝不因为跨域读失败让商品列表整体 5xx。
     */
    public List<ServerSnapshot> resources(String resourceType, Long storeId) {
        if (resourceType == null || resourceType.isBlank()) {
            return List.of();
        }
        String token = tenantToken();
        if (token == null) {
            return List.of();
        }
        String uri = "/internal/resources?resourceType=" + resourceType
                + (storeId == null ? "" : "&storeId=" + storeId);
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri(uri)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(resp);
            List<ServerSnapshot> result = new ArrayList<>();
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    result.add(toServerSnapshot(node));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Resource service unreachable, degrade server name lookup to empty: resourceType={}, storeId={}",
                    resourceType, storeId, e);
            return List.of();
        }
    }

    /** 资源 JSON → 服务人员/资源快照（失败关闭读与列表读共用同一解析口径）。 */
    private static ServerSnapshot toServerSnapshot(JsonNode node) {
        return new ServerSnapshot(
                node.path("id").isMissingNode() || node.path("id").isNull() ? null : node.path("id").asLong(),
                node.path("name").asText(null),
                node.path("resourceCode").asText(null),
                node.path("resourceType").asText(null),
                node.path("storeId").isMissingNode() || node.path("storeId").isNull()
                        ? null : node.path("storeId").asLong(),
                node.path("status").asText(null));
    }

    /** 包厢运行状态（available=false 时 reason 为「使用中」「清洁中」等）。 */
    public record RoomState(boolean available, String state, String reason, String name) {}

    /**
     * 资源快照（服务人员 KTV_SERVER 也在 {@code res_resource} 里，与包厢同表）。
     *
     * @param resourceId   资源ID（服务型商品把它写入 {@code ord_product.server_resource_id}）
     * @param name         资源名称（服务人员姓名）
     * @param resourceCode 资源编号（如 S01）
     * @param resourceType KTV_SERVER / KTV_ROOM：只有 KTV_SERVER 能挂到服务型商品上
     * @param storeId      所属门店：必须与当前门店一致
     * @param status       ENABLED / DISABLED / MAINTENANCE：只有 ENABLED 可被关联
     */
    public record ServerSnapshot(Long resourceId, String name, String resourceCode, String resourceType,
                                 Long storeId, String status) {}

    /**
     * 门店内某类型资源的运行态**批量**视图（预约「到店分配包厢」候选列表用）。
     *
     * @param id                资源ID
     * @param name              资源/包厢名称
     * @param resourceCode      资源编号
     * @param roomTypeId        所属房型（null = 未绑定房型）
     * @param roomTypeName      房型展示名（资源域回填）
     * @param available         当前是否可分配（启用 + 无有效占用 + 非清洁中）
     * @param state             IDLE/CLEANING/OCCUPIED/MAINTENANCE 等运行态
     * @param unavailableReason 不可用原因文案（可用时为 null）
     */
    public record RoomStateView(Long id, String name, String resourceCode, Long roomTypeId, String roomTypeName,
                                boolean available, String state, String unavailableReason) {}

    /**
     * 门店内某类型资源的运行态列表（{@code GET /internal/resource-states?resourceType=&storeId=}）。
     *
     * <p><b>失败关闭</b>：候选列表是「可以分配给哪个包厢」的唯一依据，读不到就抛
     * {@code RESOURCE_STATE_UNAVAILABLE}，绝不返回空列表 —— 否则会把「房态服务不可用」
     * 伪装成「该房型没有可分配包厢」，或者更糟：让调用方以为所有包厢都可用。
     */
    public List<RoomStateView> roomStates(String resourceType, Long storeId) {
        if (resourceType == null || resourceType.isBlank()) {
            throw new BusinessException("RESOURCE_TYPE_REQUIRED", "缺少资源类型");
        }
        String token = tenantToken();
        if (token == null) {
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "缺少门店上下文，无法读取包厢房态，请重新选择门店后重试");
        }
        String uri = "/internal/resource-states?resourceType=" + resourceType
                + (storeId == null ? "" : "&storeId=" + storeId);
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri(uri)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(resp);
            List<RoomStateView> result = new ArrayList<>();
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    result.add(new RoomStateView(
                            node.path("id").isMissingNode() || node.path("id").isNull() ? null : node.path("id").asLong(),
                            node.path("name").asText(null),
                            node.path("resourceCode").asText(null),
                            node.path("roomTypeId").isMissingNode() || node.path("roomTypeId").isNull()
                                    ? null : node.path("roomTypeId").asLong(),
                            node.path("roomTypeName").asText(null),
                            node.path("available").asBoolean(true),
                            node.path("state").asText(""),
                            node.path("unavailableReason").asText(null)));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Resource service unreachable, failing closed for room states: resourceType={}, storeId={}",
                    resourceType, storeId, e);
            throw new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                    "房态服务暂时不可用，无法列出可分配包厢，请稍后重试");
        }
    }

    /** 包厢时段已被占用（业务冲突，必须让开台失败）。 */
    public static class ResourceOccupiedException extends BusinessException {
        public ResourceOccupiedException(String message) {
            super("RESOURCE_OCCUPIED", message);
        }
    }
}
