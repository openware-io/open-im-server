package com.gvchat.platform.order.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.infrastructure.tenant.PermissionGuard;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.platform.order.application.InventoryApplicationService;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryTransactionPo;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/admin/inventory")
public class InventoryController {
    private final InventoryApplicationService service;

    public InventoryController(InventoryApplicationService service) { this.service = service; }

    /**
     * 物料分页列表（权限 {@code inventory.material.view}）。
     *
     * <p>参数：{@code page}（默认 1）、{@code pageSize}（默认 20，非正或 > 200 回落到 20）、
     * {@code storeId}（缺省取上下文门店，传别的门店 403 {@code STORE_SCOPE_DENIED}）、
     * {@code status}、{@code category}、{@code keyword}（名称或编码模糊）、
     * {@code from}/{@code to}（按**物料创建时间** {@code created_at} 的闭区间，统一口径见
     * {@link TimeRangeParams}）。排序 {@code id desc}。
     * 响应为 MyBatis-Plus 分页信封（{@code records}/{@code total}/{@code current}/{@code size}）。
     */
    @GetMapping("/materials")
    public Page<InventoryMaterialPo> materials(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long pageSize,
                                                @RequestParam(required = false) Long storeId,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to) {
        PermissionGuard.require("inventory.material.view");
        return service.listMaterials(page, pageSize, storeId, status, category, keyword,
                TimeRangeParams.parse(from, to));
    }

    @PostMapping("/materials")
    public InventoryMaterialPo createMaterial(@RequestBody MaterialRequest request) {
        PermissionGuard.require("inventory.material.manage");
        return service.createMaterial(toCommand(request));
    }

    @PutMapping("/materials/{id}")
    public InventoryMaterialPo updateMaterial(@PathVariable Long id, @RequestBody MaterialRequest request) {
        PermissionGuard.require("inventory.material.manage");
        return service.updateMaterial(id, toCommand(request));
    }

    @PostMapping("/receipts")
    public InventoryTransactionPo receipt(@RequestBody StockRequest request) {
        PermissionGuard.require("inventory.receipt.create");
        return service.changeStock(request.materialId(), request.quantity(), "RECEIPT", "RECEIPT", null,
                request.reason(), request.idempotencyKey(), request.unitCost());
    }

    @PostMapping("/adjustments")
    public InventoryTransactionPo adjust(@RequestBody AdjustmentRequest request) {
        PermissionGuard.require("inventory.adjust");
        String type = request.direction() == null || "IN".equalsIgnoreCase(request.direction()) ? "ADJUST_IN" : "ADJUST_OUT";
        return service.changeStock(request.materialId(), request.quantity(), type, "ADJUSTMENT", null,
                request.reason(), request.idempotencyKey(), request.unitCost());
    }

    /**
     * 出入库流水分页列表（权限 {@code inventory.transaction.view}）。
     *
     * <p>参数：{@code page}/{@code pageSize}（同物料列表口径）、{@code materialId}、
     * {@code transactionType}（RECEIPT/ADJUST_IN/ADJUST_OUT/CONSUME/REVERSE）、
     * {@code sourceType}（RECEIPT/ADJUSTMENT/ORDER_ITEM）、{@code from}/{@code to}
     * （按流水发生时刻 {@code created_at} 的闭区间，接受 {@code yyyy-MM-dd} 或 {@code yyyy-MM-ddTHH:mm:ss}；
     * {@code from > to} → 400 {@code TIME_RANGE_INVALID}，与全仓其它列表**同一错误码**）、
     * {@code keyword}（物料名称或编码模糊）。排序 {@code id desc}。
     *
     * <p>时间字符串在这里经 {@link TimeRangeParams#parse} 统一解析（日期收口到整天）后，
     * 以 {@link TimeRangeParams.TimeRange} 交给应用服务；应用层不再解析时间字符串。
     */
    @GetMapping("/transactions")
    public Page<InventoryTransactionPo> transactions(@RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "20") long pageSize,
                                                      @RequestParam(required = false) Long materialId,
                                                      @RequestParam(required = false) String transactionType,
                                                      @RequestParam(required = false) String sourceType,
                                                      @RequestParam(required = false) String from,
                                                      @RequestParam(required = false) String to,
                                                      @RequestParam(required = false) String keyword) {
        PermissionGuard.require("inventory.transaction.view");
        return service.listTransactions(page, pageSize, materialId, transactionType, sourceType,
                TimeRangeParams.parse(from, to), keyword);
    }

    /**
     * 库存成本（结存口径）：库存成本 = 结存数量 × 移动加权平均成本，按门店/物料/币种。
     * 复用 {@code inventory.material.view}（同一「看库存/成本」的岗位权限），不新增权限点以免漏配授权。
     *
     * <p>参数：{@code page}/{@code pageSize}（同物料列表口径）、{@code storeId}、{@code keyword}（物料名称或编码模糊）。
     * <b>分页口径</b>：分页作用于「物料行」，{@code total} = 命中物料条数，{@code records} 是当前页；
     * 币种信封 {@code currencyCode}/{@code mixedCurrency} 按**全部命中行**计算（翻页不漂移）。
     */
    @GetMapping("/costs")
    public InventoryApplicationService.InventoryCostReport costs(@RequestParam(defaultValue = "1") long page,
                                                                  @RequestParam(defaultValue = "20") long pageSize,
                                                                  @RequestParam(required = false) Long storeId,
                                                                  @RequestParam(required = false) String keyword) {
        PermissionGuard.require("inventory.material.view");
        return service.inventoryCosts(page, pageSize, storeId, keyword);
    }

    private InventoryApplicationService.MaterialCommand toCommand(MaterialRequest request) {
        return new InventoryApplicationService.MaterialCommand(request.storeId(), request.materialCode(), request.name(),
                request.category(), request.unit(), request.safetyStock(), request.purchasePrice(),
                request.description(), request.imageUrls(), request.mainImageUrl());
    }

    /**
     * 物料新建/编辑请求体。
     *
     * <p>{@code purchasePrice} 是**最小货币单位（分）**、每计量单位的采购价，与
     * {@code ord_product.sale_price} / {@code ord_catalog_item.unit_price} 同一口径；
     * 后台表单按「元」录入，提交前由前端换算成分。
     * 可空；null 表示不修改（新建时即未填），0 表示清空（落库 NULL），为负或超上限 → 400 PURCHASE_PRICE_INVALID。
     */
    public record MaterialRequest(Long storeId, String materialCode, String name, String category, String unit,
                                  BigDecimal safetyStock, BigDecimal purchasePrice, String description,
                                  List<String> imageUrls, String mainImageUrl) {}
    /**
     * 入库请求体。
     *
     * <p>{@code unitCost} 是**本次入库批次单价**（最小货币单位/计量单位，与 {@code purchasePrice} 同口径），
     * 可空：null 或 0 表示未填（沿用物料采购价 purchase_price），显式 &gt; 0 优先；
     * 为负或超上限 → 400 PURCHASE_PRICE_INVALID。该单价会参与移动加权平均并写入流水 unit_cost 以便追溯。
     */
    public record StockRequest(Long materialId, BigDecimal quantity, String reason, String idempotencyKey,
                               BigDecimal unitCost) {}

    /**
     * 调整请求体。{@code unitCost} 语义同 {@link StockRequest#unitCost()}：仅入库方向（ADJUST_IN）参与加权，
     * 出库方向（ADJUST_OUT）忽略（单价恒等于当前移动加权平均成本）。
     */
    public record AdjustmentRequest(Long materialId, BigDecimal quantity, String direction, String reason,
                                    String idempotencyKey, BigDecimal unitCost) {}
}
