package io.openware.platform.resource.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.domain.resource.repository.ResourceRepository;
import io.openware.platform.resource.domain.roomtype.model.RoomType;
import io.openware.platform.resource.domain.roomtype.repository.RoomTypeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 包厢房型字典（门店级）：后台「资源管理」页维护房型，包厢通过 {@code res_resource.room_type_id} 引用。
 *
 * <p>房型同时是「按房型定价」的数据源：{@code unit_price}（房费单价）与 {@code server_unit_price}
 * （服务人员单价）为最小货币单位/计费单位；为空或非正数表示该房型不定价，计费回退门店级单价
 * （tnt_pricing_plan 的门店级价格）。计价方案按 (tenant, store, resource_type) 唯一，只能表达门店级单价，
 * 所以房型级单价必须在房型字典维护，否则「按房型定价」没有可维护的权威数据。
 *
 * <p>一致性：编码/名称门店内唯一（DB 唯一键兜底并发，接口先查再插给可读的 409）；
 * 仍被包厢引用的房型禁止删除，避免包厢指向不存在的房型。
 *
 * <p>持久化经领域端口。写入用 {@link RoomTypeRepository#updateSubmitted}：它按**显式列集**更新，
 * 因为「清空房型单价」写的正是 null，而 NOT_NULL 更新策略会把 null 列整列跳过，退化成假成功。
 */
@Service
public class RoomTypeApplicationService {
    public static final int MAX_CODE_LENGTH = 32;
    public static final int MAX_NAME_LENGTH = 64;

    /** 房型状态取值：与建表注释一致（ACTIVE / DISABLED）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private final RoomTypeRepository roomTypeRepository;
    private final ResourceRepository resourceRepository;
    private final AuditClient auditClient;

    @Autowired
    public RoomTypeApplicationService(RoomTypeRepository roomTypeRepository,
                                      ResourceRepository resourceRepository,
                                      AuditClient auditClient) {
        this.roomTypeRepository = roomTypeRepository;
        this.resourceRepository = resourceRepository;
        this.auditClient = auditClient;
    }

    /** 兼容既有单测装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public RoomTypeApplicationService(RoomTypeRepository roomTypeRepository,
                                      ResourceRepository resourceRepository) {
        this(roomTypeRepository, resourceRepository, AuditClient.disabled());
    }

    /** 房型列表：按门店 + 可选状态，排序号与 id 升序（与资源列表同一排序口径）。 */
    public List<RoomType> list(Long storeId, String status) {
        TenantContext context = context();
        if (storeId != null && !storeId.equals(context.storeId())) {
            throw new ApiException(403, "STORE_SCOPE_DENIED", "无权访问该门店房型");
        }
        // status 是可选筛选：先归一成局部变量再传下去。不能把 status.trim() 直接放在调用实参位置：
        // Java 会先求值全部实参再调用，status 为 null（前端房型管理列表不带 status）时条件虽为 false，
        // status.trim() 仍会抛 NPE，被统一异常出口兜成 500。
        String statusFilter = normalizeStatusFilter(status);
        return roomTypeRepository.listInStore(context.tenantId(), context.storeId(), statusFilter);
    }

    @Transactional
    public RoomType create(RoomTypeCommand command) {
        try {
            TenantContext context = context();
            String code = requireCode(command.code());
            String name = requireName(command.name());
            requireUnique(context, code, name, null);
            // 房型图片与包厢同一套规则（最多 9 张、主图必须来自列表）；未提交图片时落空列表 + null 主图。
            ResourceMedia.Images images = ResourceMedia.normalizeImages(command.imageUrls(), command.mainImageUrl(), "房型");
            RoomType roomType = new RoomType(null, context.tenantId(), context.storeId(), code, name,
                    requireCapacity(command.capacity()), images.urls(), images.mainImageUrl(),
                    requirePrice(command.unitPrice(), "房型单价"),
                    requirePrice(command.serverUnitPrice(), "服务人员单价"),
                    command.sortOrder() == null ? 0 : command.sortOrder(),
                    requireStatus(command.status()));
            RoomType saved = roomTypeRepository.insert(roomType);
            recordRoomTypeAudit("resource.roomtype.create", saved);
            return saved;
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺门店上下文/编码名称重复/单价非法/落库失败）：审计只 WARN，异常原样抛出。
            recordRoomTypeFailure("resource.roomtype.create", command == null ? null : command.code(), failure);
            throw failure;
        }
    }

    /** 编辑房型：编码/名称/容量/单价/排序/状态；提交时才更新（null 表示本次不动该字段）。 */
    @Transactional
    public RoomType update(Long id, RoomTypeCommand command) {
        try {
            TenantContext context = context();
            RoomType current = require(id);
            String code = command.code() == null || command.code().isBlank()
                    ? current.code() : requireCode(command.code());
            String name = command.name() == null || command.name().isBlank()
                    ? current.name() : requireName(command.name());
            requireUnique(context, code, name, current.id());

            Integer capacity = command.capacity() != null
                    ? requireCapacity(command.capacity()) : current.capacity();
            List<String> imageUrls = current.imageUrls();
            String mainImageUrl = current.mainImageUrl();
            if (command.imageUrls() != null) {
                // null = 本次不改图片；空列表 = 清空图片（主图随之置空），与包厢编辑同一语义。
                ResourceMedia.Images images =
                        ResourceMedia.normalizeImages(command.imageUrls(), command.mainImageUrl(), "房型");
                imageUrls = images.urls();
                mainImageUrl = images.mainImageUrl();
            }
            // 显式传 0 表示「清空房型单价，回退门店级单价」，由 requirePrice 归一成 null；负数直接拒绝。
            Long unitPrice = command.unitPrice() != null
                    ? requirePrice(command.unitPrice(), "房型单价") : current.unitPrice();
            Long serverUnitPrice = command.serverUnitPrice() != null
                    ? requirePrice(command.serverUnitPrice(), "服务人员单价") : current.serverUnitPrice();
            Integer sortOrder = command.sortOrder() != null ? command.sortOrder() : current.sortOrder();
            String status = command.status() != null && !command.status().isBlank()
                    ? requireStatus(command.status()) : current.status();

            RoomType submitted = new RoomType(current.id(), current.tenantId(), current.storeId(), code, name,
                    capacity, imageUrls, mainImageUrl, unitPrice, serverUnitPrice, sortOrder, status);
            // 按显式列集更新：null 单价必须真的写进库（NOT_NULL 策略会跳过 null 列），否则会出现
            // 「响应已清空、库里单价还在」的假成功，该房型永远按旧价计费。
            roomTypeRepository.updateSubmitted(submitted);
            recordRoomTypeAudit("resource.roomtype.update", submitted);
            return submitted;
        } catch (RuntimeException failure) {
            // 失败出口留痕（房型不存在/归属校验失败/编码名称重复/单价非法/落库失败）。
            recordRoomTypeFailure("resource.roomtype.update", id == null ? null : String.valueOf(id), failure);
            throw failure;
        }
    }

    /**
     * 领域内失败留痕：房型写操作的失败出口此前没有留痕（BFF 拦截器只覆盖 BFF 路径），
     * 而房型单价/容量直接决定开台计费，「改失败」必须可回溯。审计只 WARN，业务异常原样抛出。
     */
    private void recordRoomTypeFailure(String action, String resourceId, RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context == null ? null : context.tenantId())
                .storeId(context == null ? null : context.storeId())
                .action(action)
                .resourceType("res_room_type")
                .resourceId(resourceId)
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .build());
    }

    /**
     * 房型增删改留痕：房型单价/容量直接影响开台计费，属于「必须可回溯」的配置变更。
     * detail 记变更后的关键字段；删除时只记被删对象快照。
     */
    private void recordRoomTypeAudit(String action, RoomType roomType) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action(action)
                .resourceType("res_room_type").resourceId(String.valueOf(roomType.id()))
                .resourceName(roomType.name())
                .idempotencyKey(action + ":" + roomType.id() + ":" + System.currentTimeMillis())
                .detailJson("{\"storeId\":" + roomType.storeId() + ",\"code\":\"" + roomType.code()
                        + "\",\"capacity\":" + roomType.capacity() + ",\"unitPrice\":" + roomType.unitPrice()
                        + ",\"serverUnitPrice\":" + roomType.serverUnitPrice() + ",\"status\":\""
                        + roomType.status() + "\",\"images\":" + roomType.imageUrlsOrEmpty().size() + "}")
                .build());
    }

    /** 删除房型：仍被包厢引用时禁止删除（否则包厢指向不存在的房型，房型单价与房型名都取不到）。 */
    @Transactional
    public void delete(Long id) {
        TenantContext context = context();
        RoomType roomType = require(id);
        long referenced = resourceRepository.countByRoomTypeId(roomType.id());
        if (referenced > 0) {
            throw new ApiException(409, "ROOM_TYPE_IN_USE",
                    "房型「" + roomType.name() + "」仍被 " + referenced + " 个包厢使用，请先改到其他房型再删除");
        }
        roomTypeRepository.deleteById(id);
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("resource.roomtype.delete")
                .resourceType("res_room_type").resourceId(String.valueOf(id))
                .resourceName(roomType.name())
                .idempotencyKey("resource.roomtype.delete:" + id)
                .detailJson("{\"storeId\":" + roomType.storeId() + ",\"code\":\"" + roomType.code() + "\"}")
                .build());
    }

    /**
     * 按 id 批量取房型（资源列表回填房型名称/单价用，避免逐行查询）。
     * 租户边界由租户拦截器自动附加，跨租户 id 不会返回。
     */
    public List<RoomType> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return roomTypeRepository.findByIds(ids);
    }

    /**
     * 按 id 取本门店房型（资源创建/更新校验 roomTypeId 归属本门店用）。
     * 不存在、跨门店或跨租户一律返回 null，由调用方决定错误码。
     */
    public RoomType findInStore(Long roomTypeId) {
        TenantContext context = context();
        return roomTypeRepository.findInStore(context.tenantId(), context.storeId(), roomTypeId).orElse(null);
    }

    private RoomType require(Long id) {
        RoomType roomType = id == null ? null : roomTypeRepository.findById(id).orElse(null);
        if (roomType == null) {
            throw new ApiException(404, "ROOM_TYPE_NOT_FOUND", "房型不存在或已被删除");
        }
        return roomType;
    }

    private void requireUnique(TenantContext context, String code, String name, Long excludeId) {
        long duplicatedCode = roomTypeRepository.countByCode(context.tenantId(), context.storeId(), code, excludeId);
        if (duplicatedCode > 0) {
            throw new ApiException(409, "ROOM_TYPE_CODE_EXISTS", "房型编码「" + code + "」已存在，请换一个编码");
        }
        long duplicatedName = roomTypeRepository.countByName(context.tenantId(), context.storeId(), name, excludeId);
        if (duplicatedName > 0) {
            throw new ApiException(409, "ROOM_TYPE_NAME_EXISTS", "房型名称「" + name + "」已存在，请换一个名称");
        }
    }

    /** 编码统一大写：MySQL 唯一索引默认大小写不敏感，若不入库前归一，「vip」与「VIP」会撞唯一键抛 500。 */
    private static String requireCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("ROOM_TYPE_INVALID", "房型编码不能为空");
        }
        String code = raw.trim().toUpperCase();
        if (code.length() > MAX_CODE_LENGTH) {
            throw new BusinessException("ROOM_TYPE_INVALID", "房型编码长度不能超过 " + MAX_CODE_LENGTH + " 个字符");
        }
        return code;
    }

    private static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("ROOM_TYPE_INVALID", "房型名称不能为空");
        }
        String name = raw.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("ROOM_TYPE_INVALID", "房型名称长度不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        return name;
    }

    private static Integer requireCapacity(Integer capacity) {
        if (capacity == null) {
            return null;
        }
        if (capacity <= 0) {
            throw new BusinessException("ROOM_TYPE_INVALID", "房型容纳人数必须大于 0");
        }
        return capacity;
    }

    /** 单价为空或正数合法；0 视为「不定价，回退门店级单价」；负数非法。 */
    private static Long requirePrice(Long price, String label) {
        if (price == null) {
            return null;
        }
        if (price < 0) {
            throw new BusinessException("ROOM_TYPE_INVALID", label + "不能为负数");
        }
        return price == 0L ? null : price;
    }

    private static String requireStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATUS_ACTIVE;
        }
        String status = raw.trim().toUpperCase();
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new BusinessException("ROOM_TYPE_INVALID", "房型状态只能是 ACTIVE 或 DISABLED");
        }
        return status;
    }

    /**
     * 列表状态筛选归一：{@code null}/空白 = 不过滤（返回全部房型，前端房型管理列表即这种调用）；
     * 仅 {@code ACTIVE}/{@code DISABLED} 合法（大小写与首尾空白容忍），其它值给可读的 400。
     */
    static String normalizeStatusFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String status = raw.trim().toUpperCase();
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new ApiException(400, "ROOM_TYPE_STATUS_INVALID", "房型状态筛选值只能是 ACTIVE 或 DISABLED");
        }
        return status;
    }

    private static TenantContext context() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.storeId() == null) {
            throw new BusinessException("SAAS_CONTEXT_REQUIRED", "缺少有效门店上下文");
        }
        return context;
    }

    /**
     * 房型创建/更新入参（status 为空按 ACTIVE）。
     *
     * <p>{@code imageUrls} 为 {@code null} 表示「本次不改图片」（更新语义，与其它可选字段一致）；
     * 空列表表示**清空图片**（主图一并置空）；非空时 {@code mainImageUrl} 必须属于该列表，
     * 不传则由 {@link ResourceMedia} 取第一张。
     */
    public record RoomTypeCommand(String code, String name, Integer capacity, Long unitPrice, Long serverUnitPrice,
                                  Integer sortOrder, String status, List<String> imageUrls, String mainImageUrl) {

        /** 不带图片的创建/更新（图片字段为 null = 本次不改图片），供只改价格/状态等场景使用。 */
        public RoomTypeCommand(String code, String name, Integer capacity, Long unitPrice, Long serverUnitPrice,
                               Integer sortOrder, String status) {
            this(code, name, capacity, unitPrice, serverUnitPrice, sortOrder, status, null, null);
        }
    }
}
