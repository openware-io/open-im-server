package com.gvchat.im.admin.projection;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminProjectionContractTest {
  private static final Path PROJECT_ROOT = Path.of("..", "..", "..").toAbsolutePath().normalize();
  private static final Path MODULE_ROOT = PROJECT_ROOT.resolve("im-services/admin/im-admin-service");

  @Test
  void projectionsUsePersistentEventIdDeduplicationAndDedicatedViews() throws IOException {
    String migration = Files.readString(MODULE_ROOT.resolve(
        "src/main/resources/db/migration/V1__adm_management_domain_baseline.sql"));
    String projection = Files.readString(MODULE_ROOT.resolve(
        "src/main/java/com/gvchat/im/admin/infra/persistence/projection/AdminProjectionQueryAdapter.java"));

    assertTrue(migration.contains("adm_projection_event"));
    assertTrue(migration.contains("adm_user_view"));
    assertTrue(migration.contains("adm_message_view"));
    assertTrue(migration.contains("adm_conversation_view"));
    assertTrue(migration.contains("PRIMARY KEY (event_id)"));
    assertTrue(projection.contains("insertIfAbsent"));
  }

  @Test
  void crossDomainReadsUseInternalApisUntilProjectionContractsAreComplete() throws IOException {
    String users = Files.readString(MODULE_ROOT.resolve(
        "src/main/java/com/gvchat/im/admin/application/query/AdminUserApplicationService.java"));
    String messages = Files.readString(MODULE_ROOT.resolve(
        "src/main/java/com/gvchat/im/admin/application/query/AdminMessageApplicationService.java"));
    String stats = Files.readString(MODULE_ROOT.resolve(
        "src/main/java/com/gvchat/im/admin/application/query/AdminStatsApplicationService.java"));

    assertTrue(users.contains("adminReadClient.listUsers"));
    assertTrue(users.contains("adminReadClient.getUser"));
    assertTrue(messages.contains("adminReadClient.listMessages"));
    assertTrue(stats.contains("adminReadClient.userStats"));
    assertTrue(stats.contains("adminReadClient.messageStats"));
    assertTrue(stats.contains("adminReadClient.conversationStats"));
  }
}
