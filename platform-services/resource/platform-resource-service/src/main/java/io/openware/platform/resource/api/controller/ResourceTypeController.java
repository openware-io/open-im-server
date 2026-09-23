package io.openware.platform.resource.api.controller;

import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.platform.resource.application.RoomTypeApplicationService;
import io.openware.platform.resource.application.RoomTypeApplicationService.RoomTypeCommand;
import io.openware.platform.resource.infra.persistence.po.RoomTypePo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 房型字典管理（后台「资源/包厢管理」页的房型维护入口）。
 *
 * <p>挂在已路由、已做会话鉴权与门店上下文保护的 {@code /admin/resources/**} 前缀下，
 * 复用资源既有权限码 {@code resource.manage}（列表与 {@code /admin/resources} 同口径不做额外鉴权），
 * 不新增菜单与权限。外部路径 {@code /api/v1/admin/resources/types/**}。
 */
@RestController
@RequestMapping("/admin/resources/types")
public class ResourceTypeController {
    private final RoomTypeApplicationService roomTypeService;

    public ResourceTypeController(RoomTypeApplicationService roomTypeService) {
        this.roomTypeService = roomTypeService;
    }

    /** 房型列表（ADM，可选 storeId/status；租户与门店边界由上下文与租户拦截器保证）。 */
    @GetMapping
    public List<RoomTypePo> list(@RequestParam(required = false) Long storeId,
                                 @RequestParam(required = false) String status) {
        return roomTypeService.list(storeId, status);
    }

    /** 新建房型（ADM）：编码/名称门店内唯一，重复返回 409。 */
    @PostMapping
    public RoomTypePo create(@RequestBody RoomTypeRequest request) {
        PermissionGuard.require("resource.manage");
        return roomTypeService.create(toCommand(request));
    }

    /** 编辑房型（ADM）：编码/名称/容量/图片/房型单价/服务人员单价/排序/状态。 */
    @PutMapping("/{id}")
    public RoomTypePo update(@PathVariable Long id, @RequestBody RoomTypeRequest request) {
        PermissionGuard.require("resource.manage");
        return roomTypeService.update(id, toCommand(request));
    }

    /** 删除房型（ADM）：仍被包厢引用时返回 409，需先把包厢改到其他房型。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        PermissionGuard.require("resource.manage");
        roomTypeService.delete(id);
    }

    private static RoomTypeCommand toCommand(RoomTypeRequest request) {
        return request == null ? new RoomTypeCommand(null, null, null, null, null, null, null, null, null)
                : new RoomTypeCommand(request.code(), request.name(), request.capacity(), request.unitPrice(),
                        request.serverUnitPrice(), request.sortOrder(), request.status(), request.imageUrls(),
                        request.mainImageUrl());
    }

    /**
     * 房型创建/更新请求体；字段为 null 表示「本次不修改」（创建时编码与名称为必填）。
     *
     * <p>{@code imageUrls} / {@code mainImageUrl} 与包厢图片同一套规则（最多 9 张、URL ≤ 512、
     * 主图必须来自列表）：C 端「选择包厢类型」按房型展示，房型自带样板图能真实反映包厢效果；
     * 传空数组表示清空图片，缺字段表示本次不动图片。
     */
    public record RoomTypeRequest(String code, String name, Integer capacity, Long unitPrice, Long serverUnitPrice,
                                  Integer sortOrder, String status, List<String> imageUrls, String mainImageUrl) {}
}
