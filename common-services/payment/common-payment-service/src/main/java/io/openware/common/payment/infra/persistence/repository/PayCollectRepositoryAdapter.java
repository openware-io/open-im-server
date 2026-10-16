package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.collect.model.PayCollect;
import io.openware.common.payment.domain.collect.repository.PayCollectRepository;
import io.openware.common.payment.infra.persistence.mapper.PayCollectMapper;
import io.openware.common.payment.infra.persistence.po.PayCollectPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link PayCollectRepository} 的持久化适配器。
 *
 * <p>{@code claimFailedAsInit} 与 {@code updateStateAndSnapshot} 都是**带状态条件的单条 UPDATE**：
 * 本表没有版本列，状态本身就是唯一的并发判据。
 */
@Repository
public class PayCollectRepositoryAdapter implements PayCollectRepository {

  private final PayCollectMapper mapper;

  public PayCollectRepositoryAdapter(PayCollectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<PayCollect> listConfirmedByOrderIds(Long tenantId, List<Long> orderIds) {
    return query(tenantId, orderIds, true);
  }

  @Override
  public List<PayCollect> listByOrderIds(Long tenantId, List<Long> orderIds) {
    return query(tenantId, orderIds, false);
  }

  @Override
  public Optional<PayCollect> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    return mapper.selectList(Wrappers.<PayCollectPo>lambdaQuery()
            .eq(PayCollectPo::getTenantId, tenantId)
            .eq(PayCollectPo::getIdempotencyKey, idempotencyKey))
        .stream().findFirst().map(PayCollectRepositoryAdapter::toDomain);
  }

  @Override
  public PayCollect insert(PayCollect collect) {
    LocalDateTime now = LocalDateTime.now();
    PayCollectPo po = toPo(collect);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // 唯一键冲突必须原样抛出：调用方靠它区分"我建成了"与"并发请求先建成了"。
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean claimFailedAsInit(Long collectId) {
    // 守卫条件是状态：受影响 0 行 = 另一并发重试已接管。清空 response_json 是接管的一部分，
    // 否则读方会把一次进行中的重跑误判成"已失败（带补偿快照）"而重复补偿。
    return mapper.update(null, Wrappers.<PayCollectPo>lambdaUpdate()
        .eq(PayCollectPo::getId, collectId)
        .eq(PayCollectPo::getState, PayCollect.STATE_FAILED)
        .set(PayCollectPo::getState, PayCollect.STATE_INIT)
        .set(PayCollectPo::getResponseJson, null)
        .set(PayCollectPo::getUpdatedAt, LocalDateTime.now())) == 1;
  }

  @Override
  public boolean updateStateAndSnapshot(PayCollect collect) {
    // 只写状态、快照与更新时间：收款单的身份（租户/单号/订单/幂等键/币种快照/请求快照）不可变，
    // 整体保存会把"哪次尝试建立的"这些事实一起改写。
    return mapper.update(null, Wrappers.<PayCollectPo>lambdaUpdate()
        .eq(PayCollectPo::getId, collect.id())
        .set(PayCollectPo::getState, collect.state())
        .set(PayCollectPo::getResponseJson, collect.responseJson())
        .set(PayCollectPo::getUpdatedAt, LocalDateTime.now())) == 1;
  }

  private List<PayCollect> query(Long tenantId, List<Long> orderIds, boolean confirmedOnly) {
    if (orderIds == null || orderIds.isEmpty()) {
      return List.of();
    }
    return mapper.selectList(Wrappers.<PayCollectPo>lambdaQuery()
            .eq(PayCollectPo::getTenantId, tenantId)
            .in(PayCollectPo::getOrderId, orderIds)
            // 只取已确认收款：INIT/FAILED 是尝试而非收款，算进"已收金额"会让订单看起来收过钱。
            .eq(confirmedOnly, PayCollectPo::getState, PayCollect.STATE_CONFIRMED)
            .orderByAsc(PayCollectPo::getCreatedAt)
            .orderByAsc(PayCollectPo::getId))
        .stream().map(PayCollectRepositoryAdapter::toDomain).toList();
  }

  private static PayCollectPo toPo(PayCollect collect) {
    PayCollectPo po = new PayCollectPo();
    po.setId(collect.id());
    po.setTenantId(collect.tenantId());
    po.setStoreId(collect.storeId());
    po.setBusinessType(collect.businessType());
    po.setCollectNo(collect.collectNo());
    po.setOrderId(collect.orderId());
    po.setIdempotencyKey(collect.idempotencyKey());
    po.setState(collect.state());
    po.setCurrencyCode(collect.currencyCode());
    po.setRequestJson(collect.requestJson());
    po.setResponseJson(collect.responseJson());
    return po;
  }

  private static PayCollect toDomain(PayCollectPo po) {
    return new PayCollect(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
        po.getCollectNo(), po.getOrderId(), po.getIdempotencyKey(), po.getState(), po.getCurrencyCode(),
        po.getRequestJson(), po.getResponseJson(), po.getCreatedAt(), po.getUpdatedAt());
  }
}
