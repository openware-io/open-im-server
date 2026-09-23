package io.openware.platform.admin.api.controller;

import io.openware.platform.admin.api.backend.AdminBackend;
import io.openware.platform.admin.application.AdminBackendApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 统一登录入口：登录后按账号权限路由到 IM 后台 / 平台运营后台 / 租户后台。 */
@RestController
@RequestMapping("/admin")
public class AdminBackendController {
    private final AdminBackendApplicationService backendService;

    public AdminBackendController(AdminBackendApplicationService backendService) { this.backendService = backendService; }

    @GetMapping("/backends")
    public List<AdminBackend> backends() {
        return backendService.backends();
    }
}
