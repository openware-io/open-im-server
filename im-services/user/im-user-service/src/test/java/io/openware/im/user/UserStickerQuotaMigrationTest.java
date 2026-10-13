package io.openware.im.user;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UserStickerQuotaMigrationTest {
  @Test
  void shouldCreateStickerQuotaInInitialSchema() throws IOException {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__user_baseline.sql"));

    assertTrue(migration.contains("CREATE TABLE `user_sticker_quota`"));
    // 基线重建后主键是自增 id，原业务键 user_id 降为唯一键。
    assertTrue(migration.contains("PRIMARY KEY (`id`)"));
    assertTrue(migration.contains("UNIQUE KEY `uk_user_sticker_quota_user` (`user_id`)"));
    assertTrue(migration.contains("`sticker_count` int unsigned NOT NULL DEFAULT '0'"));
  }
}
