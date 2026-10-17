package io.openware.platform.order.api.controller;

import io.openware.platform.order.application.CatalogApplicationService;
import io.openware.platform.order.application.CatalogApplicationService.CatalogItemCommand;
import io.openware.platform.order.application.dto.CatalogItemView;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * 商品/服务目录：B 端点单与 C 端自助加项的统一目录。
 * 列表端点 C 端（business）与 B 端共用；写端点需商户经营权限。
 *
 * <p>列表可点性/图片兜底/库存可用量，以及目录项的新建、改价、软删与审计都在
 * {@link CatalogApplicationService}；本类只做协议转换。
 *
 * <p>视图字段集与原持久化对象一致（JSON 契约不变），但三个**非列**字段
 * （available / unavailableReason / availableQuantity）只存在于视图里——
 * 它们由响应期计算，永远不会进入任何列引用。
 */
@RestController
@RequestMapping("/business/catalog")
public class CatalogController {

    private final CatalogApplicationService catalogService;

    public CatalogController(CatalogApplicationService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * 目录列表（按分类 + 门店；仅 ACTIVE）。
     * 下单列表的数据源只展示「商品管理里已上架」的商品：目录项必须关联商品，且该商品 status=ON_SHELF。
     * 服务类目录项不占库存、未关联商品也可点；不可点的项标记 available=false + 原因而不是隐藏。
     */
    @GetMapping("/items")
    public List<CatalogItemView> list(@RequestParam(required = false) String category,
                                      @RequestParam(required = false) Long storeId) {
        return catalogService.list(category, storeId);
    }

    /** 新增目录项（B 端商户经营）。 */
    @PostMapping("/items")
    public CatalogItemView create(@RequestBody CatalogItemRequest req) {
        return catalogService.create(new CatalogItemCommand(req.storeId(), req.category(), req.itemType(),
                req.name(), req.unit(), req.unitPrice(), req.description(), req.sortOrder(),
                req.imageUrls(), req.mainImageUrl()));
    }

    /** 更新目录项（B 端商户经营）。 */
    @PutMapping("/items/{id}")
    public CatalogItemView update(@PathVariable Long id, @RequestBody CatalogItemRequest req) {
        return catalogService.update(id, new CatalogItemCommand(req.storeId(), req.category(), req.itemType(),
                req.name(), req.unit(), req.unitPrice(), req.description(), req.sortOrder(),
                req.imageUrls(), req.mainImageUrl()));
    }

    /** 停用目录项（软删 status → INACTIVE）。 */
    @DeleteMapping("/items/{id}")
    public void disable(@PathVariable Long id) {
        catalogService.disable(id);
    }

    public record CatalogItemRequest(Long storeId, String category, String itemType, String name,
                                     String unit, BigDecimal unitPrice, String description, Integer sortOrder,
                                     List<String> imageUrls, String mainImageUrl) {}
}
