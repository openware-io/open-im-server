package io.openware.platform.admin.infra.persistence.repository;

import io.openware.platform.admin.domain.port.ReportQueryRepository;
import io.openware.platform.admin.infra.persistence.mapper.ReportMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * {@link ReportQueryRepository} 的持久化适配器：把领域端口的调用委托给 MyBatis {@code ReportMapper}。
 *
 * <p>报表查询的 SQL 与 MyBatis 注解散布在 mapper 中，本层只做透传，使 API 层不再直接依赖持久化实现。
 */
@Repository
public class ReportQueryRepositoryAdapter implements ReportQueryRepository {

  private final ReportMapper reportMapper;

  public ReportQueryRepositoryAdapter(ReportMapper reportMapper) {
    this.reportMapper = reportMapper;
  }

  @Override
  public List<Map<String, Object>> selectCashierOpenOrders(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectCashierOpenOrders(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectCashierCollections(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectCashierCollections(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectCashierAddOns(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectCashierAddOns(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectServerPerformance(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectServerPerformance(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectOperations(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectOperations(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectRefunds(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectRefunds(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectCollections(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectCollections(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectResourceUtilization(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectResourceUtilization(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectItemRevenue(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectItemRevenue(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectInventoryConsumptionCost(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectInventoryConsumptionCost(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectSalesOrders(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectSalesOrders(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectSalesPayments(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds) {
    return reportMapper.selectSalesPayments(tenantId, storeId, from, to, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectSalesItems(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, String itemCategory, int limit) {
    return reportMapper.selectSalesItems(tenantId, storeId, from, to, itemCategory, limit);
  }

  @Override
  public long countSalesDetails(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, String status) {
    return reportMapper.countSalesDetails(tenantId, storeId, from, to, status);
  }

  @Override
  public List<Map<String, Object>> selectSalesDetails(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, String status, int limit,
      long offset, int businessDayShiftSeconds) {
    return reportMapper.selectSalesDetails(
        tenantId, storeId, from, to, status, limit, offset, businessDayShiftSeconds);
  }

  @Override
  public List<Map<String, Object>> selectOrderPaymentComposition(Long tenantId, List<Long> orderIds) {
    return reportMapper.selectOrderPaymentComposition(tenantId, orderIds);
  }
}
