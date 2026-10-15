package io.openware.platform.admin.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 报表查询端口。
 *
 * <p>报表是面向读取的聚合查询：结果直接是列投影（{@code Map}），不是领域实体，因此这里如实按
 * 投影暴露，而不为每张报表臆造领域模型。此前 {@code ReportController} 直接注入 MyBatis
 * {@code ReportMapper} 并调用其 16 个方法，使 API 层绑定了持久化实现；改为依赖本端口后，SQL 与
 * MyBatis 注解留在基础设施层（见 {@code ReportQueryRepositoryAdapter}），分层边界得以恢复。
 *
 * <p>方法语义与原 mapper 查询一一对应，名称保持一致以便对照 SQL。
 */
public interface ReportQueryRepository {

  List<Map<String, Object>> selectCashierOpenOrders(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectCashierCollections(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectCashierAddOns(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectServerPerformance(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectOperations(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectRefunds(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectCollections(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectResourceUtilization(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectItemRevenue(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectInventoryConsumptionCost(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectSalesOrders(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectSalesPayments(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, int businessDayShiftSeconds);

  List<Map<String, Object>> selectSalesItems(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, String itemCategory, int limit);

  long countSalesDetails(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, String status);

  List<Map<String, Object>> selectSalesDetails(
      Long tenantId, Long storeId, LocalDateTime from, LocalDateTime to, String status, int limit,
      long offset, int businessDayShiftSeconds);

  List<Map<String, Object>> selectOrderPaymentComposition(Long tenantId, List<Long> orderIds);
}
