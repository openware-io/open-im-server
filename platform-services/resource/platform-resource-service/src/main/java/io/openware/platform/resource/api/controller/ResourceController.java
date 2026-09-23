package io.openware.platform.resource.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.sql.SqlScriptUtils;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.openware.platform.resource.application.ResourceMedia;
import io.openware.platform.resource.application.RoomTypeApplicationService;
import io.openware.platform.resource.infra.persistence.mapper.ResourceMapper;
import io.openware.platform.resource.infra.persistence.po.ResourcePo;
import io.openware.platform.resource.infra.persistence.po.RoomTypePo;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/resources")
public class ResourceController {
    /** 区域自由文本上限，与 res_resource.area_name 的 varchar(64) 一致。 */
    static final int MAX_AREA_NAME_LENGTH = 64;

    /**
     * image_urls 是 JSON 列：{@code LambdaUpdateWrapper.set(...)} 生成的 {@code #{}} 占位符按运行时参数类型
     * 找 TypeHandler，{@code List} 没有内置处理器，必须显式带上与 {@code ResourcePo.imageUrls} 同款的
     * {@link JacksonTypeHandler}，否则更新会在参数绑定时报「找不到 TypeHandler」。
     */
    private static final String IMAGE_URLS_TYPE_HANDLER =
            SqlScriptUtils.mappingTypeHandler(JacksonTypeHandler.class);

    private final ResourceMapper resourceMapper;
    private final RoomTypeApplicationService roomTypeService;
    private final AuditClient auditClient;

    @Autowired
    public ResourceController(ResourceMapper resourceMapper, RoomTypeApplicationService roomTypeService,
                              AuditClient auditClient) {
        this.resourceMapper = resourceMapper;
        this.roomTypeService = roomTypeService;
        this.auditClient = auditClient;
    }

    /** 兼容既有单测装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public ResourceController(ResourceMapper resourceMapper, RoomTypeApplicationService roomTypeService) {
        this(resourceMapper, roomTypeService, AuditClient.disabled());
    }

    /**
     * 资源列表（ADM，可选 resourceType/storeId；租户边界自动过滤）。含 imageUrls/mainImageUrl/description，
     * 以及区域（areaName）与房型（roomTypeId/roomTypeCode/roomTypeName/房型单价）。
     */
    @GetMapping
    public List<ResourcePo> list(@RequestParam(required = false) String resourceType,
                                 @RequestParam(required = false) Long storeId,
                                 @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to) {
        TimeRange range = TimeRangeParams.parse(from, to);
        LambdaQueryWrapper<ResourcePo> qw = new LambdaQueryWrapper<>();
        if (resourceType != null && !resourceType.isBlank()) {
            qw.eq(ResourcePo::getResourceType, resourceType);
        }
        if (storeId != null) {
            qw.eq(ResourcePo::getStoreId, storeId);
        }
        qw.ge(range.hasFrom(), ResourcePo::getCreatedAt, range.fromInclusive());
        qw.le(range.hasTo(), ResourcePo::getCreatedAt, range.toInclusive());
        qw.orderByAsc(ResourcePo::getId);
        return enrichRoomTypes(resourceMapper.selectList(qw));
    }

    /**
     * 创建资源（ADM，KTV_ROOM/KTV_SERVER）。资源归属门店以运行上下文为准，请求里的门店必须一致。
     * 图片/描述与商品同规则：最多 9 张、主图必须属于列表（未指定时取第一张）、描述 ≤255。
     * 区域（areaName）≤64；房型（roomTypeId）必须属于本门店，否则 400；
     * roomTypeId 传 null 或 0 都表示「不指定房型」（与编辑清空同一语义）。
     */
    @PostMapping
    public ResourcePo create(@RequestBody CreateResourceRequest req) {
        try {
            PermissionGuard.require("resource.manage");
            TenantContext context = requireContext();
            // 只按上下文建资源：否则「在 A 门店上下文里勾选 B 门店」会被静默写成 A 门店的资源（数据错位）。
            if (req.storeId() != null && !context.storeId().equals(req.storeId())) {
                throw new ApiException(403, "STORE_SCOPE_DENIED", "所选门店与当前运营上下文不一致，请先切换门店上下文");
            }
            if (req.resourceType() == null || req.resourceType().isBlank()) {
                throw new ApiException(400, "RESOURCE_TYPE_REQUIRED", "缺少资源类型");
            }
            String resourceCode = req.resourceCode() == null ? "" : req.resourceCode().trim();
            String name = req.name() == null ? "" : req.name().trim();
            if (resourceCode.isBlank()) {
                throw new ApiException(400, "RESOURCE_CODE_REQUIRED", "缺少资源编号");
            }
            if (name.isBlank()) {
                throw new ApiException(400, "RESOURCE_NAME_REQUIRED", "缺少资源名称");
            }
            // 同门店同类型编号唯一：重复时给可读的 409，而不是把数据库唯一键冲突抛成 500。
            requireResourceCodeAvailable(context.storeId(), req.resourceType(), resourceCode, null);
            ResourceMedia.Images images = ResourceMedia.normalizeImages(req.imageUrls(), req.mainImageUrl());
            ResourcePo po = new ResourcePo();
            po.setTenantId(context.tenantId());
            po.setStoreId(context.storeId());
            po.setResourceType(req.resourceType());
            po.setResourceCode(resourceCode);
            po.setName(name);
            po.setAreaName(normalizeAreaName(req.areaName()));
            po.setCapacity(req.capacity());
            po.setRoomTypeId(resolveRoomTypeId(req.roomTypeId()));
            po.setStatus("ENABLED");
            po.setImageUrls(images.urls());
            po.setMainImageUrl(images.mainImageUrl());
            po.setDescription(ResourceMedia.normalizeDescription(req.description()));
            po.setCreatedAt(LocalDateTime.now());
            po.setUpdatedAt(LocalDateTime.now());
            resourceMapper.insert(po);
            // 包厢（资源）新建/编辑直接影响开台计费与占用门禁，属「必须可回溯」的配置变更，写统一审计。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("resource.create")
                    .resourceType("res_resource").resourceId(String.valueOf(po.getId()))
                    .resourceName(po.getName())
                    .idempotencyKey("resource-create:" + po.getId())
                    .detailJson("{\"storeId\":" + po.getStoreId() + ",\"resourceType\":" + jsonText(po.getResourceType())
                            + ",\"resourceCode\":" + jsonText(po.getResourceCode()) + ",\"roomTypeId\":"
                            + po.getRoomTypeId() + "}")
                    .build());
            return enrichRoomTypes(List.of(po)).get(0);
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/越权门店/编号冲突/房型非法/落库失败）：审计只 WARN，异常原样抛出。
            recordFailure("resource.create", req == null ? null : req.resourceCode(), failure);
            throw failure;
        }
    }

    /**
     * 编辑资源（ADM）：名称/区域/容量/房型/图片/描述。编号与类型不可改（避免同门店编号唯一键冲突与类型漂移）。
     * 按「提交才更新」处理：字段为 null 表示本次不动；图片与描述传空表示清空，areaName 传空白表示清空，
     * roomTypeId 传 0 表示清空房型（回到门店级单价），与创建时传 null/0 = 不指定房型保持一致。
     *
     * <p>写库必须用 {@link LambdaUpdateWrapper} 显式列出本次要写的列，不能再用 {@code updateById(po)}：
     * MyBatis-Plus 默认按 {@code FieldStrategy.NOT_NULL} 把 null 字段整列跳过，而「清空」写的正是 null，
     * 于是 {@code PUT {"roomTypeId":0}} 响应看似已解绑、库里 room_type_id 依旧存在，DELETE 房型永远 409
     * {@code ROOM_TYPE_IN_USE}（后台再也删不掉已绑定的房型）。区域(area_name)、主图(main_image_url)、
     * 描述(description) 同理：传空白字符串归一成 null 后也必须真正落库为 NULL，否则只清响应不清库。
     * 显式 SET 同时保证「未提交的列一个都不写、显式提交 0/空值的列写 NULL」两种语义都成立。
     */
    @PutMapping("/{id}")
    public ResourcePo update(@PathVariable Long id, @RequestBody UpdateResourceRequest req) {
        try {
            return doUpdate(id, req);
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/越权门店/资源不存在/房型非法/落库失败）：审计只 WARN，异常原样抛出。
            recordFailure("resource.update", id == null ? null : String.valueOf(id), failure);
            throw failure;
        }
    }

    private ResourcePo doUpdate(Long id, UpdateResourceRequest req) {
        PermissionGuard.require("resource.manage");
        TenantContext context = requireContext();
        ResourcePo po = id == null ? null : resourceMapper.selectById(id);
        if (po == null) {
            throw new ApiException(404, "RESOURCE_NOT_FOUND", "资源不存在或已被删除");
        }
        if (!context.storeId().equals(po.getStoreId())) {
            throw new ApiException(403, "STORE_SCOPE_DENIED", "该资源不属于当前门店上下文，请先切换门店");
        }
        // 审计留痕需要变更前后值：房型/区域/容量直接影响开台计费与占用，必须能回溯（见方法末尾 recordAsync）。
        String beforeName = po.getName();
        String beforeAreaName = po.getAreaName();
        Integer beforeCapacity = po.getCapacity();
        String beforeRoomTypeId = po.getRoomTypeId() == null ? null : String.valueOf(po.getRoomTypeId());
        // 只写本次提交的列；值为 null 的列同样进 SET（清空），这正是 updateById 做不到的。
        LambdaUpdateWrapper<ResourcePo> update = new LambdaUpdateWrapper<ResourcePo>().eq(ResourcePo::getId, id);
        if (req.name() != null && !req.name().isBlank()) {
            po.setName(req.name().trim());
            update.set(ResourcePo::getName, po.getName());
        }
        if (req.areaName() != null) {
            po.setAreaName(normalizeAreaName(req.areaName()));
            update.set(ResourcePo::getAreaName, po.getAreaName());
        }
        if (req.capacity() != null) {
            po.setCapacity(req.capacity());
            update.set(ResourcePo::getCapacity, po.getCapacity());
        }
        if (req.roomTypeId() != null) {
            Long roomTypeId = resolveRoomTypeId(req.roomTypeId());
            po.setRoomTypeId(roomTypeId);
            update.set(ResourcePo::getRoomTypeId, roomTypeId);
            if (roomTypeId == null) {
                // 清空房型：连同只读回填字段一起清掉，避免响应里残留上一次的房型名/单价。
                // 这几个字段是 @TableField(exist = false)，只影响本次响应体，不需要进 SET。
                po.setRoomTypeCode(null);
                po.setRoomTypeName(null);
                po.setRoomTypeUnitPrice(null);
                po.setRoomTypeServerUnitPrice(null);
            }
        }
        if (req.imageUrls() != null) {
            ResourceMedia.Images images = ResourceMedia.normalizeImages(req.imageUrls(), req.mainImageUrl());
            po.setImageUrls(images.urls());
            po.setMainImageUrl(images.mainImageUrl());
            update.set(ResourcePo::getImageUrls, images.urls(), IMAGE_URLS_TYPE_HANDLER);
            update.set(ResourcePo::getMainImageUrl, images.mainImageUrl());
        }
        if (req.description() != null) {
            po.setDescription(ResourceMedia.normalizeDescription(req.description()));
            update.set(ResourcePo::getDescription, po.getDescription());
        }
        po.setUpdatedAt(LocalDateTime.now());
        update.set(ResourcePo::getUpdatedAt, po.getUpdatedAt());
        resourceMapper.update(null, update);
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("resource.update")
                .resourceType("res_resource").resourceId(String.valueOf(po.getId()))
                .resourceName(po.getName())
                .idempotencyKey("resource-update:" + po.getId() + ":" + System.currentTimeMillis())
                .detailJson("{\"before\":{\"name\":" + jsonText(beforeName) + ",\"areaName\":" + jsonText(beforeAreaName)
                        + ",\"capacity\":" + beforeCapacity + ",\"roomTypeId\":" + rawJson(beforeRoomTypeId) + "},\"after\":{"
                        + "\"name\":" + jsonText(po.getName()) + ",\"areaName\":" + jsonText(po.getAreaName())
                        + ",\"capacity\":" + po.getCapacity() + ",\"roomTypeId\":" + rawJson(po.getRoomTypeId() == null ? null : String.valueOf(po.getRoomTypeId())) + "}}")
                .build());
        return enrichRoomTypes(List.of(po)).get(0);
    }

    /** 区域自由文本：空白归一为 null（未设置），超长 400。 */
    static String normalizeAreaName(String raw) {        if (raw == null) {
            return null;
        }
        String area = raw.trim();
        if (area.isEmpty()) {
            return null;
        }
        if (area.length() > MAX_AREA_NAME_LENGTH) {
            throw new ApiException(400, "AREA_NAME_TOO_LONG", "区域名称长度不能超过 " + MAX_AREA_NAME_LENGTH + " 个字符");
        }
        return area;
    }

    /** 审计 detailJson 里的文本字段：null 落成 JSON null，其余加引号并转义，保证 detail 是合法 JSON。 */
    private static String jsonText(String raw) {
        if (raw == null) {
            return "null";
        }
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 审计 detailJson 里的数值/可空字段（如 roomTypeId）：null 落成 JSON null，其余原样输出。 */
    private static String rawJson(String raw) {
        return raw == null ? "null" : raw;
    }

    /**
     * 房型入参归一（创建与更新同一语义）：{@code null} 或 {@code 0} 都表示「不指定 / 清空房型」，
     * 统一落库为 null（回退门店级单价）；其余值必须是本门店房型，否则 400。
     *
     * <p>创建与更新必须一致：前端「创建传 null、编辑清空传 0」，此前创建对 0 抛
     * {@code ROOM_TYPE_INVALID}、更新把 0 当清空，两端语义相反。
     */
    private Long resolveRoomTypeId(Long roomTypeId) {
        if (roomTypeId == null || roomTypeId == 0L) {
            return null;
        }
        return requireRoomTypeIdInStore(roomTypeId);
    }

    /** 房型必须存在于当前门店：否则会把包厢挂到别的门店/租户的房型上（房型单价取错）。返回校验通过的房型 ID。 */
    private Long requireRoomTypeIdInStore(Long roomTypeId) {
        if (roomTypeId == null) {
            return null;
        }
        if (roomTypeService.findInStore(roomTypeId) == null) {
            throw new ApiException(400, "ROOM_TYPE_INVALID", "房型不存在或不属于当前门店，请重新选择");
        }
        return roomTypeId;
    }

    /** 回填房型的编码/名称/单价（列表与详情都要展示房型，并作为「按房型定价」的依据）。 */
    private List<ResourcePo> enrichRoomTypes(List<ResourcePo> resources) {
        if (resources == null || resources.isEmpty()) {
            return resources == null ? List.of() : resources;
        }
        Set<Long> roomTypeIds = resources.stream()
                .map(ResourcePo::getRoomTypeId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (roomTypeIds.isEmpty()) {
            return resources;
        }
        Map<Long, RoomTypePo> byId = roomTypeService.listByIds(roomTypeIds).stream()
                .collect(Collectors.toMap(RoomTypePo::getId, Function.identity(), (first, second) -> first));
        for (ResourcePo resource : resources) {
            applyRoomType(resource, byId.get(resource.getRoomTypeId()));
        }
        return resources;
    }

    static void applyRoomType(ResourcePo resource, RoomTypePo roomType) {
        if (resource == null || roomType == null) {
            return;
        }
        resource.setRoomTypeCode(roomType.getCode());
        resource.setRoomTypeName(roomType.getName());
        resource.setRoomTypeUnitPrice(roomType.getUnitPrice());
        resource.setRoomTypeServerUnitPrice(roomType.getServerUnitPrice());
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.storeId() == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少有效的租户/门店上下文，请先在右上角选择门店");
        }
        return context;
    }

    private void requireResourceCodeAvailable(Long storeId, String resourceType, String resourceCode, Long excludeId) {
        LambdaQueryWrapper<ResourcePo> qw = new LambdaQueryWrapper<ResourcePo>()
                .eq(ResourcePo::getStoreId, storeId)
                .eq(ResourcePo::getResourceType, resourceType)
                .eq(ResourcePo::getResourceCode, resourceCode);
        if (excludeId != null) {
            qw.ne(ResourcePo::getId, excludeId);
        }
        Long duplicated = resourceMapper.selectCount(qw);
        if (duplicated != null && duplicated > 0) {
            throw new ApiException(409, "RESOURCE_CODE_EXISTS", "编号「" + resourceCode + "」已存在，请换一个编号");
        }
    }

    /**
     * 领域内失败留痕：包厢/资源写操作的失败出口此前完全没有留痕（BFF 拦截器只覆盖 BFF 路径），
     * 而资源变更直接决定开台计费与占用门禁，「改失败」同样必须可回溯。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     */
    private void recordFailure(String action, String resourceId, RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context == null ? null : context.tenantId())
                .storeId(context == null ? null : context.storeId())
                .action(action)
                .resourceType("res_resource")
                .resourceId(resourceId)
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .build());
    }

    public record CreateResourceRequest(Long tenantId, Long storeId, String resourceType, String resourceCode,
                                        String name, String areaName, Integer capacity, Long roomTypeId,
                                        List<String> imageUrls, String mainImageUrl, String description) {}

    public record UpdateResourceRequest(String name, String areaName, Integer capacity, Long roomTypeId,
                                        List<String> imageUrls, String mainImageUrl, String description) {}
}
