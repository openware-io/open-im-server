package io.openware.platform.order.api.controller;

import io.openware.platform.order.application.OrderBillAccessService;
import io.openware.platform.order.application.dto.BillResult;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.GetMapping;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.PathVariable;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.RequestMapping;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户消费账单（只读）：GET /business/orders/{id}/bill，分级明细（KTV_BUSINESS_01 §9）。
 *
 * <p>授权口径与账单构建在 {@link OrderBillAccessService}；本类只做端点映射。
 */
@ClientAudience({"saas-c", "saas-b"})
@RestController
@RequestMapping("/business/orders/{orderId}")
public class OrderBillController {

    private final OrderBillAccessService billAccessService;

    public OrderBillController(OrderBillAccessService billAccessService) {
        this.billAccessService = billAccessService;
    }

    @GetMapping("/bill")
    public BillResult bill(@PathVariable Long orderId) {
        return billAccessService.bill(orderId);
    }
}
