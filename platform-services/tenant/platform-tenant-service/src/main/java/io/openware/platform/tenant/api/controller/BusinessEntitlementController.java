package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.BusinessEntitlementApplicationService;
import io.openware.platform.tenant.infra.persistence.po.BusinessEntitlementPo;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户业态开通：总部业务组合和门店工作台的可见性来源。 */
@RestController
@RequestMapping("/admin/tenant/businesses")
public class BusinessEntitlementController {

    private final BusinessEntitlementApplicationService service;

    public BusinessEntitlementController(BusinessEntitlementApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<BusinessEntitlementView> list() {
        return service.list().stream().map(BusinessEntitlementView::from).toList();
    }

    @PostMapping("/{businessType}/enable")
    public BusinessEntitlementView enable(@PathVariable String businessType) {
        return BusinessEntitlementView.from(service.enable(businessType));
    }

    public record BusinessEntitlementView(Long id, String businessType, String status, String source) {
        static BusinessEntitlementView from(BusinessEntitlementPo po) {
            return new BusinessEntitlementView(po.getId(), po.getBusinessType(), po.getStatus(), po.getSource());
        }
    }
}
