package com.gvchat.platform.resource.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.sql.SqlScriptUtils;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.resource.infra.persistence.mapper.ResourceMapper;
import com.gvchat.platform.resource.infra.persistence.mapper.RoomTypeMapper;
import com.gvchat.platform.resource.infra.persistence.po.ResourcePo;
import com.gvchat.platform.resource.infra.persistence.po.RoomTypePo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
 */
@Service
public class RoomTypeApplicationService {
    public static final int MAX_CODE_LENGTH = 32;
    public static final int MAX_NAME_LENGTH = 64;
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    /**
     * image_urls 是 JSON 列：{@code LambdaUpdateWrapper.set(...)} 的 {@code #{}} 占位符按运行时参数类型找
     * TypeHandler，{@code List} 没有内置处理器，必须显式带上与 {@code RoomTypePo.imageUrls} 同款的
     * {@link JacksonTypeHandler}，否则更新会在参数绑定时报「找不到 TypeHandler」。
     */
    private static final String IMAGE_URLS_TYPE_HANDLER =
            SqlScriptUtils.mappingTypeHandler(JacksonTypeHandler.class);

    private final RoomTypeMapper roomTypeMapper;
    private final ResourceMapper resourceMapper;
    private final AuditClient auditClient;

    @Autowired
    public RoomTypeApplicationService(RoomTypeMapper roomTypeMapper, ResourceMapper resourceMapper,
                                      AuditClient auditClient) {
        this.roomTypeMapper = roomTypeMapper;
        this.resourceMapper = resourceMapper;
        this.auditClient = auditClient;
    }

    /** 兼容既有单测装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public RoomTypeApplicationService(RoomTypeMapper roomTypeMapper, ResourceMapper resourceMapper) {
        this(roomTypeMapper, resourceMapper, AuditClient.disabled());
    }

    /** 房型列表：按门店 + 可选状态，排序号与 id 升序（与资源列表同一排序口径）。 */
    public List<RoomTypePo> list(Long storeId, String status) {
        TenantContext context = context();
        if (storeId != null && !storeId.equals(context.storeId())) {
            throw new ApiException(403, "STORE_SCOPE_DENIED", "无权访问该门店房型");
        }
        // status 是可选筛选：必须先归一成局部变量再进 wrapper。不能把 status.trim() 写在 eq(...) 的实参位置：
        // Java 会先求值全部实参再调用，status 为 null（前端房型管理列表不带 status）时条件虽为 false，
        // status.trim() 仍会抛 NPE，被统一异常出口兜成 500。
        String statusFilter = normalizeStatusFilter(status);
        return roomTypeMapper.selectList(new LambdaQueryWrapper<RoomTypePo>()
                .eq(RoomTypePo::getTenantId, context.tenantId())
                .eq(RoomTypePo::getStoreId, context.storeId())
                .eq(statusFilter != null, RoomTypePo::getStatus, statusFilter)
                .orderByAsc(RoomTypePo::getSortOrder)
                .orderByAsc(RoomTypePo::getId));
    }

    @Transactional
    public RoomTypePo create(RoomTypeCommand command) {
        try {
            TenantContext context = context();
            String code = requireCode(command.code());
            String name = requireName(command.name());
            requireUnique(context, code, name, null);
            RoomTypePo po = new RoomTypePo();
            po.setTenantId(context.tenantId());
            po.setStoreId(context.storeId());
            po.setCode(code);
            po.setName(name);
            po.setCapacity(requireCapacity(command.capacity()));
            // 房型图片与包厢同一套规则（最多 9 张、主图必须来自列表）；未提交图片时落空列表 + null 主图。
            ResourceMedia.Images images = ResourceMedia.normalizeImages(command.imageUrls(), command.mainImageUrl(), "房型");
            po.setImageUrls(images.urls());
            po.setMainImageUrl(images.mainImageUrl());
            po.setUnitPrice(requirePrice(command.unitPrice(), "房型单价"));
            po.setServerUnitPrice(requirePrice(command.serverUnitPrice(), "服务人员单价"));
            po.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
            po.setStatus(requireStatus(command.status()));
            po.setCreatedAt(LocalDateTime.now());
            po.setUpdatedAt(LocalDateTime.now());
            roomTypeMapper.insert(po);
            recordRoomTypeAudit("resource.roomtype.create", po, null);
            return po;
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺门店上下文/编码名称重复/单价非法/落库失败）：审计只 WARN，异常原样抛出。
            recordRoomTypeFailure("resource.roomtype.create", command == null ? null : command.code(), failure);
            throw failure;
        }
    }

    /** 编辑房型：编码/名称/容量/单价/排序/状态；提交时才更新（null 表示本次不动该字段）。 */
    @Transactional
    public RoomTypePo update(Long id, RoomTypeCommand command) {
        try {
            TenantContext context = context();
            RoomTypePo po = require(id);
            String code = command.code() == null || command.code().isBlank() ? po.getCode() : requireCode(command.code());
            String name = command.name() == null || command.name().isBlank() ? po.getName() : requireName(command.name());
            requireUnique(context, code, name, po.getId());
            po.setCode(code);
            po.setName(name);
            if (command.capacity() != null) {
                po.setCapacity(requireCapacity(command.capacity()));
            }
            if (command.imageUrls() != null) {
                // null = 本次不改图片；空列表 = 清空图片（主图随之置空），与包厢编辑同一语义。
                ResourceMedia.Images images = ResourceMedia.normalizeImages(command.imageUrls(), command.mainImageUrl(), "房型");
                po.setImageUrls(images.urls());
                po.setMainImageUrl(images.mainImageUrl());
            }
            if (command.unitPrice() != null) {
                // 显式传 0/负数表示「清空房型单价，回退门店级单价」以外的非法输入：0 视为清空，负数直接拒绝。
                po.setUnitPrice(requirePrice(command.unitPrice(), "房型单价"));
            }
            if (command.serverUnitPrice() != null) {
                po.setServerUnitPrice(requirePrice(command.serverUnitPrice(), "服务人员单价"));
            }
            if (command.sortOrder() != null) {
                po.setSortOrder(command.sortOrder());
            }
            if (command.status() != null && !command.status().isBlank()) {
                po.setStatus(requireStatus(command.status()));
            }
            po.setUpdatedAt(LocalDateTime.now());
            // 必须显式列出本次要写的列：MyBatis-Plus 默认 FieldStrategy.NOT_NULL 会把 null 字段整列跳过，
            // 而「清空房型单价」写的正是 null（接口把 0 归一成 null = 回退门店级单价），用 updateById(po)
            // 会出现「响应已清空、库里单价还在」的假成功，于是该房型永远按旧价计费，后台也无法取消定价。
            // po 由 selectById 载入、只被本次提交的字段覆盖，因此未提交的列写回原值，语义与原来一致。
            roomTypeMapper.update(null, new LambdaUpdateWrapper<RoomTypePo>()
                    .eq(RoomTypePo::getId, po.getId())
                    .set(RoomTypePo::getCode, po.getCode())
                    .set(RoomTypePo::getName, po.getName())
                    .set(RoomTypePo::getCapacity, po.getCapacity())
                    .set(RoomTypePo::getImageUrls, po.getImageUrls(), IMAGE_URLS_TYPE_HANDLER)
                    .set(RoomTypePo::getMainImageUrl, po.getMainImageUrl())
                    .set(RoomTypePo::getUnitPrice, po.getUnitPrice())
                    .set(RoomTypePo::getServerUnitPrice, po.getServerUnitPrice())
                    .set(RoomTypePo::getSortOrder, po.getSortOrder())
                    .set(RoomTypePo::getStatus, po.getStatus())
                    .set(RoomTypePo::getUpdatedAt, po.getUpdatedAt()));
            recordRoomTypeAudit("resource.roomtype.update", po, command);
            return po;
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
    private void recordRoomTypeAudit(String action, RoomTypePo po, RoomTypeCommand command) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action(action)
                .resourceType("res_room_type").resourceId(String.valueOf(po.getId()))
                .resourceName(po.getName())
                .idempotencyKey(action + ":" + po.getId() + ":" + System.currentTimeMillis())
                .detailJson("{\"storeId\":" + po.getStoreId() + ",\"code\":\"" + po.getCode() + "\",\"capacity\":"
                        + po.getCapacity() + ",\"unitPrice\":" + po.getUnitPrice() + ",\"serverUnitPrice\":"
                        + po.getServerUnitPrice() + ",\"status\":\"" + po.getStatus() + "\",\"images\":"
                        + (po.getImageUrls() == null ? 0 : po.getImageUrls().size()) + "}")
                .build());
    }

    /** 删除房型：仍被包厢引用时禁止删除（否则包厢指向不存在的房型，房型单价与房型名都取不到）。 */
    @Transactional
    public void delete(Long id) {
        TenantContext context = context();
        RoomTypePo po = require(id);
        Long referenced = resourceMapper.selectCount(new LambdaQueryWrapper<ResourcePo>()
                .eq(ResourcePo::getTenantId, context.tenantId())
                .eq(ResourcePo::getStoreId, context.storeId())
                .eq(ResourcePo::getRoomTypeId, po.getId()));
        if (referenced != null && referenced > 0) {
            throw new ApiException(409, "ROOM_TYPE_IN_USE",
                    "房型「" + po.getName() + "」仍被 " + referenced + " 个包厢使用，请先改到其他房型再删除");
        }
        roomTypeMapper.deleteById(id);
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("resource.roomtype.delete")
                .resourceType("res_room_type").resourceId(String.valueOf(id))
                .resourceName(po.getName())
                .idempotencyKey("resource.roomtype.delete:" + id)
                .detailJson("{\"storeId\":" + po.getStoreId() + ",\"code\":\"" + po.getCode() + "\"}")
                .build());
    }

    /**
     * 按 id 批量取房型（资源列表回填房型名称/单价用，避免逐行查询）。
     * 租户边界由租户拦截器自动附加，跨租户 id 不会返回。
     */
    public List<RoomTypePo> listByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return roomTypeMapper.selectBatchIds(ids);
    }

    /**
     * 按 id 取本门店房型（资源创建/更新校验 roomTypeId 归属本门店用）。
     * 不存在、跨门店或跨租户一律返回 null，由调用方决定错误码。
     */
    public RoomTypePo findInStore(Long roomTypeId) {
        TenantContext context = context();
        return roomTypeMapper.selectOne(new LambdaQueryWrapper<RoomTypePo>()
                .eq(RoomTypePo::getTenantId, context.tenantId())
                .eq(RoomTypePo::getStoreId, context.storeId())
                .eq(RoomTypePo::getId, roomTypeId)
                .last("LIMIT 1"));
    }

    private RoomTypePo require(Long id) {
        RoomTypePo po = id == null ? null : roomTypeMapper.selectById(id);
        if (po == null) {
            throw new ApiException(404, "ROOM_TYPE_NOT_FOUND", "房型不存在或已被删除");
        }
        return po;
    }

    private void requireUnique(TenantContext context, String code, String name, Long excludeId) {
        LambdaQueryWrapper<RoomTypePo> byCode = new LambdaQueryWrapper<RoomTypePo>()
                .eq(RoomTypePo::getTenantId, context.tenantId())
                .eq(RoomTypePo::getStoreId, context.storeId())
                .eq(RoomTypePo::getCode, code);
        if (excludeId != null) {
            byCode.ne(RoomTypePo::getId, excludeId);
        }
        Long duplicatedCode = roomTypeMapper.selectCount(byCode);
        if (duplicatedCode != null && duplicatedCode > 0) {
            throw new ApiException(409, "ROOM_TYPE_CODE_EXISTS", "房型编码「" + code + "」已存在，请换一个编码");
        }
        LambdaQueryWrapper<RoomTypePo> byName = new LambdaQueryWrapper<RoomTypePo>()
                .eq(RoomTypePo::getTenantId, context.tenantId())
                .eq(RoomTypePo::getStoreId, context.storeId())
                .eq(RoomTypePo::getName, name);
        if (excludeId != null) {
            byName.ne(RoomTypePo::getId, excludeId);
        }
        Long duplicatedName = roomTypeMapper.selectCount(byName);
        if (duplicatedName != null && duplicatedName > 0) {
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
