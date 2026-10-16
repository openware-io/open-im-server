package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.OrderQuery;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link OrderRepository} 的持久化适配器。
 *
 * <p><b>五条条件语句逐字保留</b>：{@code updateAmountsAndStatusWithVersion} / {@code updateStatusGuarded} /
 * {@code holdIfNotHeld} / {@code unholdIfHeld} / {@code voidIfStatus} 直接调用 {@code OrderMapper} 上已有的
 * {@code @Update} 语句（条件分别是 {@code AND version = ?}、{@code AND status = ?}、{@code hold_at IS NULL}、
 * {@code hold_at IS NOT NULL}、{@code AND status = ?}，版本自增写在 SQL 里）。本适配器只做
 * 「领域聚合 ↔ 持久化对象」的搬运与「受影响行数 → boolean」的翻译，
 * <b>不改写、不拆分、不合并、不在 Java 侧重算版本</b>：{@code ord_order} 没有 {@code @Version} 注解、
 * 工程里也没有乐观锁拦截器，把条件拆掉换成 {@code updateById} 会让并发写静默互相覆盖。
 * 受影响 0 行 = 并发冲突信号，原样交给调用方，本层不吞、不重试。
 *
 * <p><b>13 个非列字段绝不进入写入</b>：{@code OrderPo} 上那些
 * {@code sessionId}/{@code roomResourceId}/{@code roomName}/… 是 {@code @TableField(exist = false)}
 * 的会话投影（DDL 里没有这些列）。领域模型 {@link Order} 不承载它们，{@link #toPo} 也一个都不设：
 * 对这类字段生成列引用会让 MyBatis-Plus 在**运行时**抛
 * {@code can not find lambda cache for this property [...]}（{@code test-compile} 完全干净，
 * 钱包切片的 CAS 适配器栽过一次）。查询侧只用真实列（{@code created_at}/{@code customer_id}/…）建条件。
 *
 * <p><b>审计列由本层负责</b>：{@code created_at/updated_at} 在插入与条件写时填「现在」
 * （条件语句的 {@code updated_at} 来自端口参数，因为业务上它就是那一次动作的时刻）；
 * {@code created_by/updated_by} 只在插入时取调用方给的操作人，其余路径不写（改造前也不写）。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_order}）：
 * id / tenant_id / organization_id / store_id / order_no / idempotency_key / business_type / customer_id /
 * status / currency_code / subtotal_amount / discount_amount / tax_amount / total_amount / paid_amount /
 * refundable_amount / completed_at / cancelled_at / hold_reason / hold_at / version
 * + created_by / created_at / updated_by / updated_at。
 */
@Repository
public class OrderRepositoryAdapter implements OrderRepository {

    private final OrderMapper mapper;

    public OrderRepositoryAdapter(OrderMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Order> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id)).map(OrderRepositoryAdapter::toDomain);
    }

    @Override
    public List<Order> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            // 空集合不能下推：会拼出非法的 IN ()，调用方按「没有订单」处理。
            return List.of();
        }
        List<OrderPo> rows = mapper.selectByIds(ids);
        return rows == null ? List.of() : rows.stream().map(OrderRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Optional<Order> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        if (tenantId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            // 不带幂等键的调用方保持既有行为（返回空），也避免空串匹配到别人的行。
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<OrderPo>lambdaQuery()
            .eq(OrderPo::getTenantId, tenantId)
            .eq(OrderPo::getIdempotencyKey, idempotencyKey)
            .last("LIMIT 1"))).map(OrderRepositoryAdapter::toDomain);
    }

    @Override
    public List<Persisted<Order>> list(OrderQuery query) {
        OrderQuery effective = query == null ? OrderQuery.all() : query;
        // 条件直接落在列上（created_at 不用函数包裹，保持索引可用）：与改造前的调用点逐字一致。
        LambdaQueryWrapper<OrderPo> wrapper = Wrappers.<OrderPo>lambdaQuery()
            .eq(effective.tenantId() != null, OrderPo::getTenantId, effective.tenantId())
            .eq(effective.customerId() != null, OrderPo::getCustomerId, effective.customerId())
            .ge(effective.createdAtFromInclusive() != null, OrderPo::getCreatedAt,
                effective.createdAtFromInclusive())
            .le(effective.createdAtToInclusive() != null, OrderPo::getCreatedAt,
                effective.createdAtToInclusive())
            .lt(effective.createdAtBefore() != null, OrderPo::getCreatedAt, effective.createdAtBefore());
        if (effective.ordering() == OrderQuery.Ordering.CREATED_AT_DESC) {
            wrapper.orderByDesc(OrderPo::getCreatedAt);
        }
        return persistedList(mapper.selectList(wrapper));
    }

    @Override
    public Optional<Long> findTenantIdById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        // 跨租户鉴别：语句本身忽略租户行拦截（@InterceptorIgnore），只看主键。
        return Optional.ofNullable(mapper.selectTenantIdById(id));
    }

    @Override
    public Order insert(Order order, Long operatorId) {
        OrderPo po = toPo(order);
        if (po.getVersion() == null) {
            po.setVersion(0);
        }
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        // created_by/updated_by 取调用方给的操作人；null = 调用方没有可记的操作人（DDL 默认 0 = 系统）。
        po.setCreatedBy(operatorId);
        po.setUpdatedBy(operatorId);
        mapper.insert(po);
        // 主键由 MyBatis-Plus 回填到持久化对象上；领域模型不可变，用 withId 返回新实例。
        return order.withId(po.getId());
    }

    @Override
    public boolean updateAmountsAndStatusWithVersion(Order order, int expectedVersion) {
        OrderPo po = toPo(order);
        // 条件语句的 updated_at 来自本字段：不填会写成 NULL，所以必须给「现在」。
        po.setUpdatedAt(LocalDateTime.now());
        return mapper.updateAmountsAndStatusWithVersion(po, expectedVersion) > 0;
    }

    @Override
    public boolean updateStatusGuarded(Long id, String expectedStatus, String newStatus, LocalDateTime at) {
        return mapper.updateStatusGuarded(id, expectedStatus, newStatus, at) > 0;
    }

    @Override
    public boolean holdIfNotHeld(Long id, LocalDateTime holdAt, String reason, LocalDateTime at) {
        return mapper.holdIfNotHeld(id, holdAt, reason, at) > 0;
    }

    @Override
    public boolean unholdIfHeld(Long id, LocalDateTime at) {
        return mapper.unholdIfHeld(id, at) > 0;
    }

    @Override
    public boolean voidIfStatus(Long id, String expectedStatus, LocalDateTime at) {
        return mapper.voidIfStatus(id, expectedStatus, at) > 0;
    }

    /** 领域聚合 → 持久化对象：逐列对应真实列；13 个会话投影字段一个都不设（见类注释）。 */
    static OrderPo toPo(Order order) {
        OrderPo po = new OrderPo();
        po.setId(order.id());
        po.setTenantId(order.tenantId());
        po.setOrganizationId(order.organizationId());
        po.setStoreId(order.storeId());
        po.setOrderNo(order.orderNo());
        po.setIdempotencyKey(order.idempotencyKey());
        po.setBusinessType(order.businessType());
        po.setCustomerId(order.customerId());
        po.setStatus(order.status());
        po.setCurrencyCode(order.currencyCode());
        po.setSubtotalAmount(order.subtotalAmount());
        po.setDiscountAmount(order.discountAmount());
        po.setTaxAmount(order.taxAmount());
        po.setTotalAmount(order.totalAmount());
        po.setPaidAmount(order.paidAmount());
        po.setRefundableAmount(order.refundableAmount());
        po.setCompletedAt(order.completedAt());
        po.setCancelledAt(order.cancelledAt());
        po.setHoldReason(order.holdReason());
        po.setHoldAt(order.holdAt());
        po.setVersion(order.version());
        return po;
    }

    /** 持久化对象 → 领域聚合（只取业务列；审计四列被丢弃，需要时经 {@link #persisted(OrderPo)} 取）。 */
    static Order toDomain(OrderPo po) {
        if (po == null) {
            return null;
        }
        return new Order(po.getId(), po.getTenantId(), po.getOrganizationId(), po.getStoreId(), po.getOrderNo(),
            po.getIdempotencyKey(), po.getBusinessType(), po.getCustomerId(), po.getStatus(),
            po.getCurrencyCode(), po.getSubtotalAmount(), po.getDiscountAmount(), po.getTaxAmount(),
            po.getTotalAmount(), po.getPaidAmount(), po.getRefundableAmount(), po.getCompletedAt(),
            po.getCancelledAt(), po.getHoldReason(), po.getHoldAt(), po.getVersion());
    }

    /** 业务聚合 + 记账快照：分开交给上层（员工业绩报表要按 created_by 归集开单员工）。 */
    static Persisted<Order> persisted(OrderPo po) {
        return new Persisted<>(toDomain(po), new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(),
            po.getUpdatedBy(), po.getUpdatedAt()));
    }

    private static List<Persisted<Order>> persistedList(List<OrderPo> rows) {
        List<Persisted<Order>> result = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (OrderPo row : rows) {
                result.add(persisted(row));
            }
        }
        return result;
    }
}
