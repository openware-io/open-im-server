package io.openware.platform.order.api.controller;

import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.platform.order.application.ProductCategoryApplicationService;
import io.openware.platform.order.infra.persistence.po.ProductCategoryPo;
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
 * 商品分类管理（商品管理页的「分类管理」入口）。
 *
 * <p>挂在已路由、已做门店上下文保护的 {@code /api/v1/admin/products/**} 前缀下，
 * 复用商品既有权限码（product.view / product.manage），不新增菜单与权限。</p>
 */
@RestController
@RequestMapping("/admin/products/categories")
public class ProductCategoryController {
    private final ProductCategoryApplicationService service;

    public ProductCategoryController(ProductCategoryApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductCategoryPo> list(@RequestParam(required = false) Long storeId,
                                        @RequestParam(required = false) String status) {
        PermissionGuard.require("product.view");
        return service.list(storeId, status);
    }

    @PostMapping
    public ProductCategoryPo create(@RequestBody CategoryRequest request) {
        PermissionGuard.require("product.manage");
        return service.create(toCommand(request));
    }

    @PutMapping("/{id}")
    public ProductCategoryPo update(@PathVariable Long id, @RequestBody CategoryRequest request) {
        PermissionGuard.require("product.manage");
        return service.update(id, toCommand(request));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        PermissionGuard.require("product.manage");
        service.delete(id);
    }

    private static ProductCategoryApplicationService.CategoryCommand toCommand(CategoryRequest request) {
        return new ProductCategoryApplicationService.CategoryCommand(request.name(), request.sortOrder(), request.status());
    }

    public record CategoryRequest(String name, Integer sortOrder, String status) {}
}
