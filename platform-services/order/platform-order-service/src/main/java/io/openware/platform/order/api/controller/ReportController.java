package io.openware.platform.order.api.controller;

import io.openware.platform.order.application.OrderReportApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 经营/业绩/资源利用率只读报表（E6 完成定义：只读聚合，标注数据时点，不建领域表）。
 * 外部路径 /api/v1/reports/**（Gateway StripPrefix=2 → /reports/**）。
 *
 * <p>聚合、币种分组与时间口径在 {@link OrderReportApplicationService}；本类只做端点映射。
 */
@RestController
@RequestMapping("/reports")
public class ReportController {
    private final OrderReportApplicationService reportService;

    public ReportController(OrderReportApplicationService reportService) {
        this.reportService = reportService;
    }

    /** 经营报表：按「门店 + 币种」聚合订单数、应收、实收（跨币种不合并，逐币种一行）。 */
    @GetMapping("/store-operation")
    public Map<String, Object> storeOperation(@RequestParam LocalDate businessDate) {
        return reportService.storeOperation(businessDate);
    }

    /** 资源利用率：按包厢聚合占用时长（秒）与翻台次数，并带出最近一次会话的人数/服务人员。 */
    @GetMapping("/resource-utilization")
    public Map<String, Object> resourceUtilization(@RequestParam LocalDate businessDate) {
        return reportService.resourceUtilization(businessDate);
    }

    /** 员工业绩（D-13）：按「开单员工 + 币种」聚合开单数/应收/实收。 */
    @GetMapping("/employee-performance")
    public Map<String, Object> employeePerformance(@RequestParam LocalDate businessDate) {
        return reportService.employeePerformance(businessDate);
    }
}
