package io.openware.platform.admin.api.controller;

import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.platform.admin.application.AdminReportApplicationService;
import io.openware.platform.admin.application.AdminReportApplicationService.EmployeePerformanceReport;
import io.openware.platform.admin.application.AdminReportApplicationService.InventoryGrossProfitReport;
import io.openware.platform.admin.application.AdminReportApplicationService.OperationsReport;
import io.openware.platform.admin.application.AdminReportApplicationService.PaymentsReport;
import io.openware.platform.admin.application.AdminReportApplicationService.ResourceUtilizationReport;
import io.openware.platform.admin.application.AdminReportApplicationService.SalesItemsReport;
import io.openware.platform.admin.application.AdminReportApplicationService.SalesReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报表只读聚合端点（ADM-15 / E6 / D-13）。
 *
 * <p>时间口径、营业日窗口、分桶、币种分组与各报表的聚合规则都在
 * {@link AdminReportApplicationService}；本类只做端点映射与参数透传。
 */
@RestController
@RequestMapping("/admin/reports")
public class ReportController {

    private final AdminReportApplicationService reportService;

    public ReportController(AdminReportApplicationService reportService) {
        this.reportService = reportService;
    }

    /** 报表接口统一权限入口：菜单隐藏不是授权，接口本身必须拒绝无权限账号。 */
    @org.springframework.web.bind.annotation.ModelAttribute
    public void requireReportPermission() {
        PermissionGuard.require("report.view");
    }

    /** 员工业绩：按员工（收银/服务人员）× 统计桶聚合开单数/服务时长/收款额/加项数。 */
    @GetMapping("/employee-performance")
    public EmployeePerformanceReport employeePerformance(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        return reportService.employeePerformance(storeId, from, to, granularity);
    }

    /** 经营报表：按门店/统计桶聚合订单数/应收/实收/退款/客单。 */
    @GetMapping("/operations")
    public OperationsReport operations(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        return reportService.operations(storeId, from, to, granularity);
    }

    /** 支付报表：按门店/统计桶聚合收款/退款（交易流水口径）。 */
    @GetMapping("/payments")
    public PaymentsReport payments(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        return reportService.payments(storeId, from, to, granularity);
    }

    /** 资源利用率：按「每次开台」一行。 */
    @GetMapping("/resources")
    public ResourceUtilizationReport resources(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        return reportService.resources(storeId, from, to, granularity);
    }

    /** 库存成本与毛利（按币种分组）。 */
    @GetMapping("/inventory-gross-profit")
    public InventoryGrossProfitReport inventoryGrossProfit(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        return reportService.inventoryGrossProfit(storeId, from, to, granularity);
    }

    /** 销售报表：统计桶 + 明细分页，同一份 from/to/granularity 口径。 */
    @GetMapping("/sales")
    public SalesReport sales(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return reportService.sales(storeId, from, to, granularity, status, page, pageSize);
    }

    /** 商品/服务销售排行。 */
    @GetMapping("/sales-items")
    public SalesItemsReport salesItems(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String itemType,
            @RequestParam(required = false) Integer topN) {
        return reportService.salesItems(storeId, from, to, itemType, topN);
    }
}
