package io.openware.im.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayV1RouteConfigurationTest {
  private static final Path CONFIG = Path.of("src", "main", "resources", "application.yml");

  @Test
  void everyPublicDomainRouteUsesV1AndStripsBothPrefixes() throws IOException {
    List<String> lines = Files.readAllLines(CONFIG);
    String config = String.join("\n", lines);
    for (String domain : List.of("users", "auth", "device-tokens", "friends",
        "user-stickers", "messages", "conversations", "channels", "secret-chats", "secret-group-chats", "groups", "rtc", "media", "admin")) {
      assertTrue(config.contains("Path=/api/v1/" + domain), "missing v1 route for " + domain);
    }
    assertTrue(config.contains("Path=/ws/im/v1"));
    assertFalse(config.contains("Path=/ws/im\n"));
    assertFalse(config.contains("Path=/api/**"));
    assertFalse(config.contains("Path=/media-public/**"), "公共媒体已迁到 /api/v1/media-public，旧路由必须删除");
    long routeFilters = lines.stream().filter(line -> line.trim().equals("- StripPrefix=2")).count();
    assertTrue(routeFilters >= 19, "every v1 API route must strip /api/v1");
    assertTrue(config.contains("http://127.0.0.1:5173"));
  }

  @Test
  void tenantOverviewIsRoutedToAdminBffBeforeTenantCatchAll() throws IOException {
    String config = Files.readString(CONFIG);
    String overviewRoute = "id: saas-admin-tenant-overview";
    String tenantRoute = "id: saas-tenant";
    int overviewIndex = config.indexOf(overviewRoute);
    int tenantIndex = config.indexOf(tenantRoute);
    assertTrue(overviewIndex >= 0, "总部总览缺少 Admin BFF 路由");
    assertTrue(config.contains("Path=/api/v1/admin/tenant/overview"), "总部总览缺少精确 v1 路径");
    assertTrue(config.indexOf("PLATFORM_ADMIN_URI", overviewIndex) > overviewIndex,
        "总部总览路由必须指向 platform-admin-service");
    assertTrue(tenantIndex >= 0 && overviewIndex < tenantIndex,
        "总部总览精确路由必须位于泛租户路由之前");
  }
}
