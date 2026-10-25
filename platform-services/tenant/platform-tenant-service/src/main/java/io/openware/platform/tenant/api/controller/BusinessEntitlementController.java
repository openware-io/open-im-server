package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.BusinessEntitlementApplicationService;
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
    public List<BusinessEntitlementApplicationService.BusinessEntitlementView> list() {
        return service.list();
    }

    @PostMapping("/{businessType}/enable")
    public BusinessEntitlementApplicationService.BusinessEntitlementView enable(@PathVariable String businessType) {
        return service.enable(businessType);
    }

}
