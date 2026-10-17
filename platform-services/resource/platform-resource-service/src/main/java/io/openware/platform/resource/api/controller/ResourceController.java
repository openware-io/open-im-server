package io.openware.platform.resource.api.controller;

import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.resource.api.converter.ResourceConverter;
import io.openware.platform.resource.api.dto.ResourceView;
import io.openware.platform.resource.application.ResourceCommandApplicationService;
import io.openware.platform.resource.application.ResourceCommandApplicationService.CreateResourceCommand;
import io.openware.platform.resource.application.ResourceCommandApplicationService.UpdateResourceCommand;
import io.openware.platform.resource.application.ResourceQueryApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * 资源（包厢）管理：列表 / 创建 / 编辑。
 *
 * <p>列表查询在 {@link ResourceQueryApplicationService}；创建/编辑的权限、上下文、校验、落库与审计在
 * {@link ResourceCommandApplicationService}。本类只做参数解析、区间口径与协议转换。
 *
 * <p>清空语义必须由端口的具名方法表达（{@code clearRoomTypeBinding} / {@code updateDetails} 传空值）：
 * MyBatis-Plus 默认的 {@code FieldStrategy.NOT_NULL} 会把 null 列整列跳过，用 {@code save} 更新会出现
 * 「响应已清空、库里还在」的假成功——{@code PUT {"roomTypeId":0}} 解绑后 {@code DELETE} 房型永远 409
 * {@code ROOM_TYPE_IN_USE}。这是已修缺陷，不得退回。
 */
@RestController
@RequestMapping("/admin/resources")
public class ResourceController {
    private final ResourceQueryApplicationService resourceQuery;
    private final ResourceCommandApplicationService resourceCommandService;

    public ResourceController(ResourceCommandApplicationService resourceCommandService,
                              ResourceQueryApplicationService resourceQuery) {
        this.resourceCommandService = resourceCommandService;
        this.resourceQuery = resourceQuery;
    }

    /**
     * 资源列表（ADM，可选 resourceType/storeId；租户边界自动过滤）。含 imageUrls/mainImageUrl/description，
     * 以及区域（areaName）与房型（roomTypeId/roomTypeCode/roomTypeName/房型单价）。
     */
    @GetMapping
    public List<ResourceView> list(@RequestParam(required = false) String resourceType,
                                   @RequestParam(required = false) Long storeId,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to) {
        // 区间解析留在控制器（入站参数口径）：格式非法或 from > to 一律 400 TIME_RANGE_INVALID，
        // 与全仓其它列表同一错误码。解析出的边界再由查询用例下推到仓储端口。
        TimeRange range = TimeRangeParams.parse(from, to);
        if (range.hasFrom() || range.hasTo()) {
            return resourceQuery.listEnabledInRange(resourceType, storeId, range.fromInclusive(), range.toInclusive())
                    .stream().map(ResourceConverter::toView).toList();
        }
        return resourceQuery.listEnabled(resourceType, storeId).stream()
                .map(ResourceConverter::toView).toList();
    }

    /** 创建资源（ADM，KTV_ROOM/KTV_SERVER）。 */
    @PostMapping
    public ResourceView create(@RequestBody CreateResourceRequest req) {
        return ResourceConverter.toView(resourceCommandService.create(new CreateResourceCommand(
                req.tenantId(), req.storeId(), req.resourceType(), req.resourceCode(), req.name(),
                req.areaName(), req.capacity(), req.roomTypeId(), req.imageUrls(), req.mainImageUrl(),
                req.description())));
    }

    /** 编辑资源（ADM）：名称/区域/容量/房型/图片/描述。编号与类型不可改。 */
    @PutMapping("/{id}")
    public ResourceView update(@PathVariable Long id, @RequestBody UpdateResourceRequest req) {
        return ResourceConverter.toView(resourceCommandService.update(id, new UpdateResourceCommand(
                req.name(), req.areaName(), req.capacity(), req.roomTypeId(), req.imageUrls(),
                req.mainImageUrl(), req.description())));
    }

    public record CreateResourceRequest(Long tenantId, Long storeId, String resourceType, String resourceCode,
                                        String name, String areaName, Integer capacity, Long roomTypeId,
                                        List<String> imageUrls, String mainImageUrl, String description) {}

    public record UpdateResourceRequest(String name, String areaName, Integer capacity, Long roomTypeId,
                                        List<String> imageUrls, String mainImageUrl, String description) {}
}
