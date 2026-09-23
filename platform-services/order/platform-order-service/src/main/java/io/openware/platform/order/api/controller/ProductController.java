package io.openware.platform.order.api.controller;

import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.platform.order.application.ProductApplicationService;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/admin/products")
public class ProductController {
    private final ProductApplicationService service;
    public ProductController(ProductApplicationService service) { this.service = service; }

    /**
     * 商品列表（当前门店）。
     *
     * <p>{@code from}/{@code to} 按**商品创建时间** {@code created_at} 的闭区间筛选，统一口径见
     * {@link TimeRangeParams}：{@code yyyy-MM-dd} 的 from/to 分别收口到当天起点/当天末尾，
     * 也接受 {@code yyyy-MM-ddTHH:mm:ss}；为空 = 不筛；{@code from > to} → 400 {@code TIME_RANGE_INVALID}。
     */
    @GetMapping
    public List<ProductPo> list(@RequestParam(required = false) Long storeId,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        PermissionGuard.require("product.view");
        return service.list(storeId, status, TimeRangeParams.parse(from, to));
    }
    @PostMapping public ProductPo create(@RequestBody ProductRequest r) { PermissionGuard.require("product.manage"); return service.create(toCommand(r)); }
    @PutMapping("/{id}") public ProductPo update(@PathVariable Long id, @RequestBody ProductRequest r) { PermissionGuard.require("product.manage"); return service.update(id, toCommand(r)); }
    @PostMapping("/{id}/on-shelf") public ProductPo onShelf(@PathVariable Long id) { PermissionGuard.require("product.publish"); return service.onShelf(id); }
    @PostMapping("/{id}/off-shelf") public ProductPo offShelf(@PathVariable Long id) { PermissionGuard.require("product.unpublish"); return service.offShelf(id); }
    private ProductApplicationService.ProductCommand toCommand(ProductRequest r) { return new ProductApplicationService.ProductCommand(r.storeId(), r.productCode(), r.name(), r.category(), r.unit(), r.salePrice(), r.materialId(), r.stockControlled(), r.sortOrder(), r.description(), r.imageUrls(), r.mainImageUrl(), r.itemType(), r.serverResourceId()); }

    /**
     * 商品请求体。
     *
     * <p>{@code itemType}：PRODUCT（实物商品，缺省）/ SERVICE（服务）。
     * {@code serverResourceId}：服务型商品必填（res_resource.id，resource_type=KTV_SERVER，须同门店且启用）；
     * 实物商品会忽略该字段（落库为 NULL，不做资源域校验）。
     */
    public record ProductRequest(Long storeId, String productCode, String name, String category, String unit,
                                 BigDecimal salePrice, Long materialId, Boolean stockControlled, Integer sortOrder,
                                 String description, List<String> imageUrls, String mainImageUrl,
                                 String itemType, Long serverResourceId) {}
}
