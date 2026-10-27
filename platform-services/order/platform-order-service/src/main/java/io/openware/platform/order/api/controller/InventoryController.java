package io.openware.platform.order.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.platform.order.api.dto.InventoryMaterialView;
import io.openware.platform.order.api.dto.InventoryTransactionView;
import io.openware.platform.order.application.InventoryApplicationService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存管理 API（通用库存域：物料 / 仓库 / 余额 / 流水 / 成本 / 入库 / 出库 / 调整 / 调拨 / 盘点 / 消费）。
 *
 * <p><b>权限（V24 目标契约）</b>：读统一用 {@code inventory.view}，写统一用 {@code inventory.manage}。
 * 旧的细分码（{@code inventory.material.view} 等）不再作为访问条件——权限码迁移与岗位授权由租户域
 * 迁移负责，接口只认新码，避免两套权限并存。
 *
 * <p><b>作用域</b>：所有端点只从签名 {@code TenantContext} 取 tenant/organization/business_type/store，
 * 请求体里的 {@code organizationId}/{@code businessType} 只是**待校验声明**：与上下文不一致即 422。
 * 仓库通过 {@code warehouseId} 指定；不传则使用门店默认仓（读列表不传则覆盖门店全部仓）。
 *
 * <p><b>酒店 / KTV 共用</b>：这里没有 Hotel Inventory 独立端点/表。酒店客房领用、迷你吧销售与 KTV 商品出库
 * 都走 {@code /consumptions}（或订单加项应用服务），只传物料、数量与业务单号，不传房间号——库存不绑定房间。
 */
@RestController
@RequestMapping("/admin/inventory")
public class InventoryController {
    private final InventoryApplicationService service;

    public InventoryController(InventoryApplicationService service) { this.service = service; }

    /**
     * 物料分页列表（权限 {@code inventory.view}）。
     *
     * <p>参数：{@code page}（默认 1）、{@code pageSize}（默认 20，非正或 > 200 回落到 20）、
     * {@code storeId}（缺省取上下文门店，传别的门店 403 {@code STORE_SCOPE_DENIED}）、
     * {@code status}、{@code category}、{@code keyword}（名称或编码模糊）、
     * {@code from}/{@code to}（按**物料创建时间** {@code created_at} 的闭区间，统一口径见
     * {@link TimeRangeParams}）。排序 {@code id desc}。
     * 响应为分页信封（{@code records}/{@code total}/{@code current}/{@code size}），行是
     * {@link InventoryMaterialView}（字段名与原持久化对象一致；其中 {@code onHandQty}/{@code reservedQty}
     * 不是列，由服务在列表读路径批量回填到视图）。
     */
    @GetMapping("/materials")
    public Page<InventoryMaterialView> materials(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @RequestParam(required = false) Long storeId,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        PermissionGuard.require("inventory.view");
        return service.listMaterials(page, pageSize, storeId, status, category, keyword,
                TimeRangeParams.parse(from, to));
    }

    @PostMapping("/materials")
    public InventoryMaterialView createMaterial(@RequestBody MaterialRequest request) {
        PermissionGuard.require("inventory.manage");
        return service.createMaterial(toCommand(request));
    }

    @PutMapping("/materials/{id}")
    public InventoryMaterialView updateMaterial(@PathVariable Long id, @RequestBody MaterialRequest request) {
        PermissionGuard.require("inventory.manage");
        return service.updateMaterial(id, toCommand(request));
    }

    /** 入库（权限 {@code inventory.manage}）。 */
    @PostMapping("/receipts")
    public InventoryTransactionView receipt(@RequestBody StockRequest request) {
        PermissionGuard.require("inventory.manage");
        return service.changeStock(new InventoryApplicationService.MovementCommand(request.materialId(),
                request.quantity(), "RECEIPT", "RECEIPT", null, request.reason(), request.idempotencyKey(),
                request.unitCost(), request.warehouseId(),
                new InventoryApplicationService.ScopeClaim(request.organizationId(), request.businessType())));
    }

    /** 手工出库/调整（权限 {@code inventory.manage}；{@code direction=IN} 盘盈/调增，否则盘亏/调减）。 */
    @PostMapping("/adjustments")
    public InventoryTransactionView adjust(@RequestBody AdjustmentRequest request) {
        PermissionGuard.require("inventory.manage");
        String type = request.direction() == null || "IN".equalsIgnoreCase(request.direction())
                ? "ADJUST_IN" : "ADJUST_OUT";
        return service.changeStock(new InventoryApplicationService.MovementCommand(request.materialId(),
                request.quantity(), type, "ADJUSTMENT", null, request.reason(), request.idempotencyKey(),
                request.unitCost(), request.warehouseId(),
                new InventoryApplicationService.ScopeClaim(request.organizationId(), request.businessType())));
    }

    /**
     * 出入库流水分页列表（权限 {@code inventory.view}）。
     *
     * <p>参数：{@code page}/{@code pageSize}（同物料列表口径）、{@code materialId}、
     * {@code transactionType}（RECEIPT/ADJUST_IN/ADJUST_OUT/CONSUME/TRANSFER_IN/TRANSFER_OUT/REVERSE）、
     * {@code sourceType}（RECEIPT/ADJUSTMENT/STOCKTAKE/ORDER_ITEM/ROOM_CONSUMPTION/MINIBAR_SALE/KTV_ITEM/TRANSFER）、
     * {@code warehouseId}（不传 = 门店全部仓）、{@code from}/{@code to}
     * （按流水发生时刻 {@code created_at} 的闭区间，接受 {@code yyyy-MM-dd} 或 {@code yyyy-MM-ddTHH:mm:ss}；
     * {@code from > to} → 400 {@code TIME_RANGE_INVALID}，与全仓其它列表**同一错误码**）、
     * {@code keyword}（物料名称或编码模糊）。排序 {@code id desc}。
     */
    @GetMapping("/transactions")
    public Page<InventoryTransactionView> transactions(@RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long pageSize,
                                                       @RequestParam(required = false) Long materialId,
                                                       @RequestParam(required = false) String transactionType,
                                                       @RequestParam(required = false) String sourceType,
                                                       @RequestParam(required = false) Long warehouseId,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to,
                                                       @RequestParam(required = false) String keyword) {
        PermissionGuard.require("inventory.view");
        return service.listTransactions(page, pageSize, materialId, transactionType, sourceType, warehouseId,
                TimeRangeParams.parse(from, to), keyword);
    }

    /**
     * 库存成本（结存口径，权限 {@code inventory.view}）：库存成本 = 结存数量 × 移动加权平均成本，
     * 按门店/仓库/物料/币种。
     *
     * <p>参数：{@code page}/{@code pageSize}（同物料列表口径）、{@code storeId}、{@code warehouseId}
     * （不传 = 门店全部仓）、{@code keyword}（物料名称或编码模糊）。
     * <b>分页口径</b>：分页作用于「仓库×物料行」，{@code total} = 命中行数，{@code records} 是当前页；
     * 币种信封 {@code currencyCode}/{@code mixedCurrency} 按**全部命中行**计算（翻页不漂移）。
     */
    @GetMapping("/costs")
    public InventoryApplicationService.InventoryCostReport costs(@RequestParam(defaultValue = "1") long page,
                                                                  @RequestParam(defaultValue = "20") long pageSize,
                                                                  @RequestParam(required = false) Long storeId,
                                                                  @RequestParam(required = false) Long warehouseId,
                                                                  @RequestParam(required = false) String keyword) {
        PermissionGuard.require("inventory.view");
        return service.inventoryCosts(page, pageSize, storeId, warehouseId, keyword);
    }

    /**
     * 跨门店/跨业态调拨（权限 {@code inventory.manage}）：源仓扣减、目标仓增加，两边各写一条流水
     * （TRANSFER_OUT / TRANSFER_IN，同一个调拨单号关联），同事务提交，绝不直接改数量。
     *
     * <p>目标门店必须存在且启用；目标组织/业态以门店权威快照为准，请求里的
     * {@code toOrganizationId}/{@code toBusinessType} 若填写必须与之一致（422）。
     * 目标门店需要同编码物料（或显式 {@code toMaterialId}），不会静默建档。
     */
    @PostMapping("/transfers")
    public InventoryApplicationService.TransferResult transfer(@RequestBody TransferRequest request) {
        PermissionGuard.require("inventory.manage");
        return service.transfer(new InventoryApplicationService.TransferCommand(request.materialId(),
                request.quantity(), request.toStoreId(), request.toMaterialId(), request.fromWarehouseId(),
                request.toWarehouseId(), request.reason(), request.idempotencyKey(), request.fromOrganizationId(),
                request.fromBusinessType(), request.toOrganizationId(), request.toBusinessType()));
    }

    /**
     * 库存盘点（权限 {@code inventory.manage}）：实盘数量与账面数量比对，差异落 ADJUST_IN/ADJUST_OUT
     * 流水（来源 STOCKTAKE）；无差异不写流水（{@code adjusted=false}），实盘不允许为负。
     */
    @PostMapping("/stocktakes")
    public InventoryApplicationService.StocktakeResult stocktake(@RequestBody StocktakeRequest request) {
        PermissionGuard.require("inventory.manage");
        return service.stocktake(new InventoryApplicationService.StocktakeCommand(request.materialId(),
                request.countedQuantity(), request.warehouseId(), request.reason(), request.idempotencyKey(),
                request.organizationId(), request.businessType()));
    }

    /**
     * 消费出库（权限 {@code inventory.manage}）：酒店客房领用 / 迷你吧销售 / KTV 商品出库 / 订单加项。
     *
     * <p>{@code scene} 取值 {@code ROOM_CONSUMPTION / MINIBAR_SALE / KTV_ITEM / ORDER_ITEM}；
     * 只传业务单号 {@code sourceId}，<b>不传房间号</b>——库存是通用域，不绑定房间。与订单/账单的协作
     * 只发生在应用服务层（扣库存 + 写来源流水），不跨表写订单/账单。
     */
    @PostMapping("/consumptions")
    public InventoryTransactionView consume(@RequestBody ConsumeRequest request) {
        PermissionGuard.require("inventory.manage");
        return service.consume(new InventoryApplicationService.ConsumeCommand(request.materialId(),
                request.quantity(), request.scene(), request.sourceId(), request.reason(),
                request.idempotencyKey(), request.warehouseId(), request.organizationId(),
                request.businessType()));
    }

    private InventoryApplicationService.MaterialCommand toCommand(MaterialRequest request) {
        return new InventoryApplicationService.MaterialCommand(request.storeId(), request.materialCode(), request.name(),
                request.category(), request.unit(), request.safetyStock(), request.purchasePrice(),
                request.description(), request.imageUrls(), request.mainImageUrl(),
                request.organizationId(), request.businessType());
    }

    /**
     * 物料新建/编辑请求体。
     *
     * <p>{@code purchasePrice} 是**最小货币单位（分）**、每计量单位的采购价，与
     * {@code ord_product.sale_price} / {@code ord_catalog_item.unit_price} 同一口径；
     * 后台表单按「元」录入，提交前由前端换算成分。
     * 可空；null 表示不修改（新建时即未填），0 表示清空（落库 NULL），为负或超上限 → 400 PURCHASE_PRICE_INVALID。
     *
     * <p>{@code organizationId}/{@code businessType} 是可选声明：与签名门店上下文不一致 → 422。
     */
    public record MaterialRequest(Long storeId, String materialCode, String name, String category, String unit,
                                  BigDecimal safetyStock, BigDecimal purchasePrice, String description,
                                  List<String> imageUrls, String mainImageUrl,
                                  Long organizationId, String businessType) {}
    /**
     * 入库请求体。
     *
     * <p>{@code unitCost} 是**本次入库批次单价**（最小货币单位/计量单位，与 {@code purchasePrice} 同口径），
     * 可空：null 或 0 表示未填（沿用物料采购价 purchase_price），显式 &gt; 0 优先；
     * 为负或超上限 → 400 PURCHASE_PRICE_INVALID。该单价会参与移动加权平均并写入流水 unit_cost 以便追溯。
     *
     * <p>{@code warehouseId} 为空取门店默认仓；{@code organizationId}/{@code businessType} 为待校验声明。
     */
    public record StockRequest(Long materialId, BigDecimal quantity, String reason, String idempotencyKey,
                               BigDecimal unitCost, Long warehouseId, Long organizationId, String businessType) {}

    /**
     * 调整请求体。{@code unitCost} 语义同 {@link StockRequest#unitCost()}：仅入库方向（ADJUST_IN）参与加权，
     * 出库方向（ADJUST_OUT）忽略（单价恒等于当前移动加权平均成本）。
     */
    public record AdjustmentRequest(Long materialId, BigDecimal quantity, String direction, String reason,
                                    String idempotencyKey, BigDecimal unitCost, Long warehouseId,
                                    Long organizationId, String businessType) {}

    /**
     * 调拨请求体。
     *
     * @param idempotencyKey 调拨单号（幂等键）；两边流水分别落 {@code key:OUT} / {@code key:IN}
     * @param toMaterialId   目标门店物料；为空时按源物料编码在目标门店映射
     * @param toStoreId      目标门店（必填，且不能等于当前门店）
     */
    public record TransferRequest(Long materialId, BigDecimal quantity, Long toStoreId, Long toMaterialId,
                                  Long fromWarehouseId, Long toWarehouseId, String reason, String idempotencyKey,
                                  Long fromOrganizationId, String fromBusinessType, Long toOrganizationId,
                                  String toBusinessType) {}

    /** 盘点请求体：{@code countedQuantity} 是实盘数量（不允许为负）。 */
    public record StocktakeRequest(Long materialId, BigDecimal countedQuantity, Long warehouseId, String reason,
                                   String idempotencyKey, Long organizationId, String businessType) {}

    /** 消费请求体：{@code scene} = ROOM_CONSUMPTION / MINIBAR_SALE / KTV_ITEM / ORDER_ITEM；不接收房间号。 */
    public record ConsumeRequest(Long materialId, BigDecimal quantity, String scene, String sourceId, String reason,
                                 String idempotencyKey, Long warehouseId, Long organizationId,
                                 String businessType) {}
}
