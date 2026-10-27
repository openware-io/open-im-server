package io.openware.platform.admin.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 后台控制器权限覆盖审计。
 *
 * <p>登录/上下文/菜单属于认证基础设施，允许只依赖会话；其余业务控制器必须显式调用
 * {@link io.openware.infrastructure.tenant.PermissionGuard} 或自身已有的权限校验方法。
 * 新增按摩、桌球等控制器时，此测试会先失败，避免“菜单隐藏但接口裸奔”。
 */
class AdminControllerPermissionCoverageTest {

    private static final Path CONTROLLER_DIR = Path.of(
            "src/main/java/io/openware/platform/admin/api/controller");

    /** 仅认证/会话/上下文/菜单端点豁免功能权限码。 */
    private static final Set<String> AUTH_ONLY = Set.of(
            "AdminLoginController.java",
            "AdminSessionController.java",
            "SsoAuthController.java",
            "ContextController.java",
            "AdminMenuController.java",
            "AdminBackendController.java");

    @Test
    void everyBusinessControllerHasAnExplicitPermissionCheck() throws IOException {
        List<Path> controllers;
        try (var stream = Files.list(CONTROLLER_DIR)) {
            controllers = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }
        for (Path controller : controllers) {
            String name = controller.getFileName().toString();
            if (AUTH_ONLY.contains(name)) {
                continue;
            }
            String source = Files.readString(controller);
            boolean guarded = source.contains("PermissionGuard.require")
                    || source.contains("requirePlatformOperator")
                    || source.contains("requireManageContext")
                    || (source.contains("requireContext") && source.contains("iam.role.manage"))
                    || source.contains("tenant.overview.view");
            assertEquals(true, guarded, "后台控制器缺少明确权限校验: " + name);
        }
    }
}
