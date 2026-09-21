package com.gvchat.platform.admin.application;

import com.gvchat.platform.admin.api.backend.AdminBackend;
import com.gvchat.platform.admin.domain.model.AdminRole;
import com.gvchat.platform.admin.infra.security.AdminContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SaaS 后台入口列表：只返回 SaaS 自己的后台（平台运营后台 / 租户后台），不含 IM 后台。
 * IM 后台是集团统一门户里与 SaaS 平级的独立入口，不在 SaaS 后台内。
 * 平台运营入口仅向平台管理员和超级管理员开放。
 */
@Service
public class AdminBackendApplicationService {

    public List<AdminBackend> backends() {
        var admin = AdminContextHolder.get();
        if (admin == null) return List.of();
        List<AdminBackend> backends = List.of(
                new AdminBackend("platform", "平台运营后台", "/platform", "building"),
                new AdminBackend("tenant", "租户后台", "/tenant", "shop")
        );
        return backends.stream().filter(backend -> !"platform".equals(backend.code())
                || admin.role() == AdminRole.SUPER_ADMIN || admin.role() == AdminRole.PLATFORM_ADMIN).toList();
    }
}
