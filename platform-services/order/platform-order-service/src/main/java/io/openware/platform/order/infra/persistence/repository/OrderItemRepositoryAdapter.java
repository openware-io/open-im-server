package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.OrderItemQuery;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.po.OrderItemPo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link OrderItemRepository} 的持久化适配器。
 *
 * <p><b>两条批量口径与审批语句逐字保留</b>：{@code selectActiveRoomFeeItemsByOrderIds}（仅 ROOM_FEE）与
 * {@code selectActiveItemsByOrderIds}（全部 ACTIVE）是**两条不同的查询**，合并会让实时合计的差额法
 * 要么漏扣房费、要么把普通加项当成房费扣掉；{@code markApproval} 的
 * {@code WHERE ... AND status = 'PENDING_APPROVAL'} 是并发的唯一判定点，
 * 拆成「先查再 updateById」会回到「先确认又拒绝」的竞态。本适配器只做搬运与行数翻译。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_order_item}）：
 * 见 {@link OrderItem}。{@code OrderItemPo} 上没有 {@code @TableField(exist = false)} 字段，
 * 因此不存在「对不存在的列生成引用」的风险（那会在运行时抛
 * {@code can not find lambda cache for this property [...]}）。
 *
 * <p><b>审计列</b>：{@code created_at/updated_at} 由本层填「现在」（插入与整实体更新）；
 * 读路径要它们时经 {@link Persisted} 交给上层——待确认加项要展示提交时间，
 * 账单要展示房费明细的「固化时刻」，而审计列不进领域模型。
 */
@Repository
public class OrderItemRepositoryAdapter implements OrderItemRepository {

    private final OrderItemMapper mapper;

    public OrderItemRepositoryAdapter(OrderItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<OrderItem> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id)).map(OrderItemRepositoryAdapter::toDomain);
    }

    @Override
    public List<Persisted<OrderItem>> list(OrderItemQuery query) {
        OrderItemQuery effective = query == null ? OrderItemQuery.ofOrder(null) : query;
        LambdaQueryWrapper<OrderItemPo> wrapper = Wrappers.<OrderItemPo>lambdaQuery()
            .eq(effective.tenantId() != null, OrderItemPo::getTenantId, effective.tenantId())
            .eq(effective.orderId() != null, OrderItemPo::getOrderId, effective.orderId())
            .eq(effective.status() != null, OrderItemPo::getStatus, effective.status())
            .eq(effective.itemType() != null, OrderItemPo::getItemType, effective.itemType())
            .eq(effective.inventoryStatus() != null, OrderItemPo::getInventoryStatus,
                effective.inventoryStatus());
        if (effective.ordering() == OrderItemQuery.Ordering.CREATED_AT_THEN_ID_ASC) {
            wrapper.orderByAsc(OrderItemPo::getCreatedAt).orderByAsc(OrderItemPo::getId);
        } else {
            wrapper.orderByAsc(OrderItemPo::getId);
        }
        if (effective.limit() != null) {
            // 行数由具名工厂给出（编译期常量），不是调用方拼进来的字符串。
            wrapper.last("LIMIT " + effective.limit());
        }
        return persistedList(mapper.selectList(wrapper));
    }

    @Override
    public List<OrderItem> listActiveRoomFeeItemsByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            // 空集合不能下推：会拼出非法的 IN ()，调用方按「没有房费明细」处理。
            return List.of();
        }
        return domainList(mapper.selectActiveRoomFeeItemsByOrderIds(orderIds));
    }

    @Override
    public List<OrderItem> listActiveByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        return domainList(mapper.selectActiveItemsByOrderIds(orderIds));
    }

    @Override
    public boolean existsPendingApproval(Long orderId) {
        if (orderId == null) {
            return false;
        }
        Long count = mapper.selectCount(Wrappers.<OrderItemPo>lambdaQuery()
            .eq(OrderItemPo::getOrderId, orderId)
            .eq(OrderItemPo::getStatus, OrderItem.STATUS_PENDING_APPROVAL));
        return count != null && count > 0;
    }

    @Override
    public OrderItem insert(OrderItem item) {
        OrderItemPo po = toPo(item);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        // created_by/updated_by 不写：DDL 是 NOT NULL DEFAULT 0（0 = 系统），改造前的实现也没写这两列。
        mapper.insert(po);
        return item.withId(po.getId());
    }

    @Override
    public void update(OrderItem item) {
        OrderItemPo po = toPo(item);
        // 整实体更新（NOT_NULL 策略）：null 列不参与 SET，因此 created_at/created_by 不会被覆盖；
        // updated_at 是记账列、不是端口参数，由本层刷成「现在」（账单会展示房费明细的固化时刻）。
        po.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(po);
    }

    @Override
    public boolean markApproval(Long tenantId, Long orderId, Long itemId, String targetStatus, Long updatedBy) {
        return mapper.markApproval(tenantId, orderId, itemId, targetStatus, updatedBy) > 0;
    }

    /** 领域聚合 → 持久化对象：逐列对应真实列（明细没有非列字段）。 */
    static OrderItemPo toPo(OrderItem item) {
        OrderItemPo po = new OrderItemPo();
        po.setId(item.id());
        po.setTenantId(item.tenantId());
        po.setStoreId(item.storeId());
        po.setBusinessType(item.businessType());
        po.setOrderId(item.orderId());
        po.setItemType(item.itemType());
        po.setCatalogItemId(item.catalogItemId());
        po.setResourceId(item.resourceId());
        po.setNameSnapshot(item.nameSnapshot());
        po.setUnitPrice(item.unitPrice());
        po.setQuantity(item.quantity());
        po.setDiscountAmount(item.discountAmount());
        po.setTaxAmount(item.taxAmount());
        po.setTotalAmount(item.totalAmount());
        po.setCurrencyCode(item.currencyCode());
        po.setPriceSnapshotJson(item.priceSnapshotJson());
        po.setStatus(item.status());
        po.setSource(item.source());
        po.setInventoryStatus(item.inventoryStatus());
        po.setInventoryMaterialId(item.inventoryMaterialId());
        po.setInventoryRecoveryDecision(item.inventoryRecoveryDecision());
        po.setInventoryRecoveryReason(item.inventoryRecoveryReason());
        po.setInventoryRecoveryDecidedBy(item.inventoryRecoveryDecidedBy());
        po.setInventoryRecoveryDecidedAt(item.inventoryRecoveryDecidedAt());
        return po;
    }

    /** 持久化对象 → 领域聚合（只取业务列；审计四列经 {@link #persisted(OrderItemPo)} 取）。 */
    static OrderItem toDomain(OrderItemPo po) {
        if (po == null) {
            return null;
        }
        return new OrderItem(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
            po.getOrderId(), po.getItemType(), po.getCatalogItemId(), po.getResourceId(), po.getNameSnapshot(),
            po.getUnitPrice(), po.getQuantity(), po.getDiscountAmount(), po.getTaxAmount(),
            po.getTotalAmount(), po.getCurrencyCode(), po.getPriceSnapshotJson(), po.getStatus(),
            po.getSource(), po.getInventoryStatus(), po.getInventoryMaterialId(),
            po.getInventoryRecoveryDecision(), po.getInventoryRecoveryReason(),
            po.getInventoryRecoveryDecidedBy(), po.getInventoryRecoveryDecidedAt());
    }

    /** 业务聚合 + 记账快照（created_at = 提交时间；updated_at = 房费明细的固化时刻）。 */
    static Persisted<OrderItem> persisted(OrderItemPo po) {
        return new Persisted<>(toDomain(po), new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(),
            po.getUpdatedBy(), po.getUpdatedAt()));
    }

    private static List<OrderItem> domainList(List<OrderItemPo> rows) {
        return rows == null ? List.of() : rows.stream().map(OrderItemRepositoryAdapter::toDomain).toList();
    }

    private static List<Persisted<OrderItem>> persistedList(List<OrderItemPo> rows) {
        List<Persisted<OrderItem>> result = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (OrderItemPo row : rows) {
                result.add(persisted(row));
            }
        }
        return result;
    }
}
