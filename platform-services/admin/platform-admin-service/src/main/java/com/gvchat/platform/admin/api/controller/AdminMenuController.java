package com.gvchat.platform.admin.api.controller;

import com.gvchat.platform.admin.api.menu.AdminMenuItem;
import com.gvchat.platform.admin.application.AdminMenuApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SaaS 后台菜单 BFF（统一登录入口：前端按返回的 scope 菜单展示对应后台）。
 */
@RestController
@RequestMapping("/admin")
public class AdminMenuController {
    private final AdminMenuApplicationService menuService;

    public AdminMenuController(AdminMenuApplicationService menuService) { this.menuService = menuService; }

    @GetMapping("/menus")
    public List<AdminMenuItem> menus(@RequestParam(required = false) String scope) {
        return menuService.menus(scope);
    }
}
