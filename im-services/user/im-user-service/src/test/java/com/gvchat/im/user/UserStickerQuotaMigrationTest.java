package com.gvchat.im.user;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UserStickerQuotaMigrationTest {
  @Test
  void shouldCreateStickerQuotaInInitialSchema() throws IOException {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__init.sql"));

    assertTrue(migration.contains("CREATE TABLE `user_sticker_quota`"));
    assertTrue(migration.contains("PRIMARY KEY (`user_id`)"));
    assertTrue(migration.contains("`sticker_count` int unsigned NOT NULL DEFAULT '0'"));
  }
}
