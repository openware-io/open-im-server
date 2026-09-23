package io.openware.platform.resource.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.infra.persistence.mapper.OccupationMapper;
import io.openware.platform.resource.infra.persistence.mapper.ResourceMapper;
import io.openware.platform.resource.infra.persistence.mapper.RoomTypeMapper;
import io.openware.platform.resource.infra.persistence.po.ResourcePo;
import io.openware.platform.resource.infra.persistence.po.RoomTypePo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资源运行状态：可用性 = 启用 且 无有效占用（HELD/RESERVED/IN_USE） 且 非清洁中。
 *
 * <p>「清洁中」是门店运营状态（结台后自动进入、服务员确认后才恢复），与资源自身的
 * 启用/停用（ENABLED/DISABLED/MAINTENANCE）是两件事，都影响可用性。
 *
 * <p>B 端房态看板还依赖两列静态属性：区域（areaName，见 res_resource.area_name）与房型
 * （roomTypeId/roomTypeName，见 res_room_type）；房型名称按 room_type_id 批量回填，避免逐行查询。
 *
 * <p>审计（2026-09 补齐）：清洁状态是「包厢能不能卖」的门禁之一（清洁中不可开台、不可预约），
 * 后台房态看板的「清洁完成」按钮此前不留痕，出现「包厢莫名不可用/莫名可用」时无法回溯是谁切的。
 * 状态真正变化时才留痕（幂等 no-op 不留），成功与失败（资源不存在）都上报统一审计。
 */
@Service
public class ResourceStateApplicationService {
    public static final String CLEANING = "CLEANING";
    public static final String IDLE = "IDLE";

    /** 审计动作码：与 {@code AuditActions} 登记的稳定码一致（前端按码筛选）。 */
    private static final String AUDIT_ACTION_CLEANING = "resource.cleaning.update";
    private static final String AUDIT_RESOURCE_TYPE = "res_resource";

    private final ResourceMapper resourceMapper;
    private final OccupationMapper occupationMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final AuditClient auditClient;

    /** 兼容既有装配/单元测试：未注入房型 Mapper 与审计客户端时房型字段回 null、审计走关闭态。 */
    public ResourceStateApplicationService(ResourceMapper resourceMapper, OccupationMapper occupationMapper) {
        this(resourceMapper, occupationMapper, null);
    }

    public ResourceStateApplicationService(ResourceMapper resourceMapper, OccupationMapper occupationMapper,
                                           RoomTypeMapper roomTypeMapper) {
        this(resourceMapper, occupationMapper, roomTypeMapper, AuditClient.disabled());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ResourceStateApplicationService(ResourceMapper resourceMapper, OccupationMapper occupationMapper,
                                           RoomTypeMapper roomTypeMapper, AuditClient auditClient) {
        this.resourceMapper = resourceMapper;
        this.occupationMapper = occupationMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.auditClient = auditClient;
    }

    /** 按类型列出资源并附带运行状态（仅启用中的资源）。 */
    public List<ResourceView> listWithState(String resourceType, Long tenantId) {
        return listWithState(resourceType, tenantId, null);
    }

    /**
     * 按类型 + 门店列出资源并附带运行状态（仅启用中的资源）。
     *
     * <p>门店过滤为 {@code null} 时不过滤（沿用历史行为）；预约「到店分配包厢」的候选列表
     * 必须带门店，否则会把别的门店的包厢也列成可分配（见 platform-order-service 的候选端点）。
     */
    public List<ResourceView> listWithState(String resourceType, Long tenantId, Long storeId) {
        LambdaQueryWrapper<ResourcePo> query = new LambdaQueryWrapper<ResourcePo>()
                .eq(ResourcePo::getResourceType, resourceType)
                .eq(ResourcePo::getStatus, "ENABLED")
                .orderByAsc(ResourcePo::getId);
        if (storeId != null) {
            query.eq(ResourcePo::getStoreId, storeId);
        }
        List<ResourcePo> resources = resourceMapper.selectList(query);
        Set<Long> occupied = tenantId == null
                ? Set.of()
                : occupationMapper.selectActiveResourceIds(tenantId).stream().collect(Collectors.toSet());
        Map<Long, RoomTypePo> roomTypes = roomTypesOf(resources);
        return resources.stream()
                .map(resource -> toView(resource, occupied.contains(resource.getId()),
                        lookup(roomTypes, resource.getRoomTypeId())))
                .toList();
    }

    /** 未设置房型（room_type_id 为 null）时不能拿 null 去查不可变 Map（Map.of() 的 get(null) 会抛 NPE）。 */
    private static RoomTypePo lookup(Map<Long, RoomTypePo> roomTypes, Long roomTypeId) {
        return roomTypeId == null ? null : roomTypes.get(roomTypeId);
    }

    /** 单个资源的运行状态视图。 */
    public ResourceView view(Long resourceId, Long tenantId) {
        ResourcePo resource = require(resourceId);
        boolean occupied = tenantId != null && occupationMapper.selectActiveResourceIds(tenantId).contains(resourceId);
        return toView(resource, occupied, roomTypeOf(resource));
    }

    /** 置清洁中 / 清洁完成（清洁中不可开台、不可预约）。 */
    @Transactional
    public ResourceView setCleaning(Long resourceId, boolean cleaning) {
        try {
            return doSetCleaning(resourceId, cleaning);
        } catch (RuntimeException failure) {
            // 失败出口留痕（资源不存在 RESOURCE_NOT_FOUND/落库失败）：审计只 WARN，异常原样抛出。
            recordCleaningFailure(resourceId, failure);
            throw failure;
        }
    }

    private ResourceView doSetCleaning(Long resourceId, boolean cleaning) {
        ResourcePo resource = require(resourceId);
        if (CLEANING.equals(resource.getCleaningStatus()) == cleaning) {
            // 幂等：状态一致直接返回，避免重复写
            return view(resourceId, resource.getTenantId());
        }
        resource.setCleaningStatus(cleaning ? CLEANING : IDLE);
        resource.setCleaningStartedAt(cleaning ? LocalDateTime.now() : null);
        resource.setUpdatedAt(LocalDateTime.now());
        resourceMapper.updateById(resource);
        recordCleaningAudit(resource, cleaning);
        return view(resourceId, resource.getTenantId());
    }

    /**
     * 清洁状态变更留痕：只在状态真正改变时上报（幂等 no-op 不算一次变更，留痕会变成噪声）。
     *
     * <p>detail 记录变更后的状态与资源编号（编号由运营填写，走 {@code jsonText} 转义后再拼），
     * 资源名走 {@code resourceName} 由 {@link AuditClient#buildBody} 统一转义。
     */
    private void recordCleaningAudit(ResourcePo resource, boolean cleaning) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(resource.getTenantId())
                .storeId(resource.getStoreId())
                .action(AUDIT_ACTION_CLEANING)
                .resourceType(AUDIT_RESOURCE_TYPE)
                .resourceId(String.valueOf(resource.getId()))
                .resourceName(resource.getName())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey(AUDIT_ACTION_CLEANING + ":" + resource.getId() + ":"
                        + resource.getUpdatedAt())
                .detailJson("{\"resourceId\":" + resource.getId() + ",\"resourceCode\":"
                        + jsonText(resource.getResourceCode()) + ",\"cleaning\":" + cleaning
                        + ",\"state\":\"" + resource.getCleaningStatus() + "\"}")
                .build());
    }

    /** 清洁状态切换失败留痕：与成功同码、{@code result=FAILURE} + 稳定错误码。 */
    private void recordCleaningFailure(Long resourceId, RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context == null ? null : context.tenantId())
                .storeId(context == null ? null : context.storeId())
                .action(AUDIT_ACTION_CLEANING)
                .resourceType(AUDIT_RESOURCE_TYPE)
                .resourceId(resourceId == null ? null : String.valueOf(resourceId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"resourceId\":" + resourceId + "}")
                .build());
    }

    /** 审计 detailJson 里的文本字段：null 落成 JSON null，其余加引号并转义，保证 detail 是合法 JSON。 */
    private static String jsonText(String raw) {
        if (raw == null) {
            return "null";
        }
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private ResourceView toView(ResourcePo resource, boolean occupied, RoomTypePo roomType) {
        boolean cleaning = CLEANING.equals(resource.getCleaningStatus());
        String reason = cleaning ? "清洁中" : occupied ? "使用中" : null;
        String state = cleaning ? CLEANING : occupied ? "OCCUPIED" : IDLE;
        // 历史数据无图/无描述：列表统一回空列表 + null，前端走占位图与空态，不因缺字段报错。
        // 区域/房型同理：未设置时回 null，前端按「未设置」展示。
        List<String> imageUrls = resource.getImageUrls() == null ? List.of() : resource.getImageUrls();
        return new ResourceView(resource.getId(), resource.getResourceCode(), resource.getName(), resource.getCapacity(),
                !cleaning && !occupied, state, reason, imageUrls, resource.getMainImageUrl(), resource.getDescription(),
                resource.getAreaName(), resource.getRoomTypeId(),
                roomType == null ? null : roomType.getName(), roomType == null ? null : roomType.getCode());
    }

    private RoomTypePo roomTypeOf(ResourcePo resource) {
        if (roomTypeMapper == null || resource == null || resource.getRoomTypeId() == null) {
            return null;
        }
        return roomTypeMapper.selectById(resource.getRoomTypeId());
    }

    private Map<Long, RoomTypePo> roomTypesOf(List<ResourcePo> resources) {
        if (roomTypeMapper == null || resources == null || resources.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = resources.stream()
                .map(ResourcePo::getRoomTypeId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return roomTypeMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(RoomTypePo::getId, Function.identity(), (first, second) -> first));
    }

    private ResourcePo require(Long resourceId) {
        ResourcePo resource = resourceId == null ? null : resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BusinessException("RESOURCE_NOT_FOUND", "资源不存在");
        }
        return resource;
    }

    /**
     * 业务资源视图：available = 启用且无有效占用且非清洁中。
     * imageUrls/mainImageUrl/description 供 B 端房态看板与 C 端选包厢展示（同源相对路径，历史数据为空）；
     * areaName 为包厢所属区域（自由文本，可为 null）；roomTypeId/roomTypeName/roomTypeCode 为包厢房型
     * （后台前端此前把「区域/房型」标为「未接入」，这里补齐；未设置房型时三个字段均为 null）。
     */
    public record ResourceView(Long id, String resourceCode, String name, Integer capacity, boolean available,
                               String state, String unavailableReason, List<String> imageUrls, String mainImageUrl,
                               String description, String areaName, Long roomTypeId, String roomTypeName,
                               String roomTypeCode) {}
}
