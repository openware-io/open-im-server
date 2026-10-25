package io.openware.platform.admin.api.controller;

import io.openware.platform.admin.application.CustomerLoginProvisioningService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户后台：为客户开通 C 端登录账号。 */
@RestController
@RequestMapping("/admin/customers")
public class CustomerLoginProvisionController {
    private final CustomerLoginProvisioningService service;

    public CustomerLoginProvisionController(CustomerLoginProvisioningService service) {
        this.service = service;
    }

    @PostMapping("/c-login")
    public ProvisionResponse provision(@RequestBody ProvisionRequest req) {
        return new ProvisionResponse(service.provision(req.phone(), req.password()));
    }

    public record ProvisionRequest(String phone, String password) {}
    public record ProvisionResponse(long accountId) {}
}
