package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.shift.model.Shift;
import io.openware.common.payment.domain.shift.repository.ShiftRepository;
import io.openware.common.payment.infra.persistence.mapper.ShiftMapper;
import io.openware.common.payment.infra.persistence.po.ShiftPo;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/** {@link ShiftRepository} 的持久化适配器。 */
@Repository
public class ShiftRepositoryAdapter implements ShiftRepository {

  private final ShiftMapper mapper;

  public ShiftRepositoryAdapter(ShiftMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<Shift> listByOpenedAtBetween(LocalDateTime fromInclusive, LocalDateTime toInclusive) {
    // 按 opened_at（交班业务时间）筛选，按 created_at 倒序（与既有列表口径一致）。
    return mapper.selectList(Wrappers.<ShiftPo>lambdaQuery()
            .ge(fromInclusive != null, ShiftPo::getOpenedAt, fromInclusive)
            .le(toInclusive != null, ShiftPo::getOpenedAt, toInclusive)
            .orderByDesc(ShiftPo::getCreatedAt))
        .stream().map(ShiftRepositoryAdapter::toDomain).toList();
  }

  @Override
  public java.util.Optional<Shift> findById(Long shiftId) {
    return java.util.Optional.ofNullable(mapper.selectById(shiftId))
        .map(ShiftRepositoryAdapter::toDomain);
  }

  @Override
  public List<Shift> listClosedBetween(Long tenantId, Long storeId, List<String> closedStatuses,
                                       LocalDateTime from, LocalDateTime to) {
    if (closedStatuses == null || closedStatuses.isEmpty()) {
      return List.of();
    }
    // 左闭右开 [from, to)：窗口端点是营业日边界，用 le(to) 会把同一笔长短款算进相邻两天。
    return mapper.selectList(Wrappers.<ShiftPo>lambdaQuery()
            .eq(ShiftPo::getTenantId, tenantId)
            .eq(ShiftPo::getStoreId, storeId)
            .in(ShiftPo::getStatus, closedStatuses)
            .ge(from != null, ShiftPo::getClosedAt, from)
            .lt(to != null, ShiftPo::getClosedAt, to))
        .stream().map(ShiftRepositoryAdapter::toDomain).toList();
  }

  @Override
  public Shift insert(Shift shift) {
    LocalDateTime now = LocalDateTime.now();
    ShiftPo po = new ShiftPo();
    po.setTenantId(shift.tenantId());
    po.setStoreId(shift.storeId());
    po.setTerminalId(shift.terminalId());
    po.setOperatorId(shift.operatorId());
    po.setOpenedAt(shift.openedAt());
    po.setOpeningCash(shift.openingCash());
    po.setExpectedCash(shift.expectedCash());
    po.setCurrencyCode(shift.currencyCode());
    po.setStatus(shift.status());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateClosing(Shift shift) {
    // 只写结账列：开班事实（终端/操作人/开班时刻/备用金/币种快照）不参与更新，
    // 否则结账时会把"开班固化的币种快照"改成结账时的租户设置。
    return mapper.update(null, Wrappers.<ShiftPo>lambdaUpdate()
        .eq(ShiftPo::getId, shift.id())
        .set(ShiftPo::getClosedAt, shift.closedAt())
        .set(ShiftPo::getExpectedCash, shift.expectedCash())
        .set(ShiftPo::getActualCash, shift.actualCash())
        .set(ShiftPo::getDifferenceAmount, shift.differenceAmount())
        .set(ShiftPo::getStatus, shift.status())
        .set(ShiftPo::getUpdatedAt, LocalDateTime.now())) > 0;
  }

  private static Shift toDomain(ShiftPo po) {
    return new Shift(po.getId(), po.getTenantId(), po.getStoreId(), po.getTerminalId(),
        po.getOperatorId(), po.getOpenedAt(), po.getClosedAt(), po.getOpeningCash(),
        po.getExpectedCash(), po.getActualCash(), po.getDifferenceAmount(), po.getCurrencyCode(),
        po.getStatus(), po.getCreatedAt(), po.getUpdatedAt());
  }
}
