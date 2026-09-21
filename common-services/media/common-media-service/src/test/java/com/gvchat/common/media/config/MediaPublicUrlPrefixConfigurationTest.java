package com.gvchat.common.media.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 出厂配置回归：media.public-url-prefix 的默认值必须落在网关 /api 命名空间。
 *
 * <p>旧默认值 /media-public 依赖各环境入口剥前缀（ACK 入口不剥前缀时 MinIO 会把 media-public 当桶名返回 403），
 * 这里锁死默认值，避免配置回退成需要 rewrite 的形态。
 */
class MediaPublicUrlPrefixConfigurationTest {

  private static final Path CONFIG = Path.of("src", "main", "resources", "application.yml");

  @Test
  void defaultPublicUrlPrefixUsesGatewayApiNamespace() throws IOException {
    List<String> prefixLines = Files.readAllLines(CONFIG).stream()
        .map(String::trim)
        .filter(line -> line.startsWith("public-url-prefix:"))
        .toList();

    assertEquals(1, prefixLines.size(), "application.yml 必须且只能有一处 media.public-url-prefix");
    assertTrue(prefixLines.get(0).endsWith("/api/v1/media-public}"),
        "media.public-url-prefix 默认值必须是 /api/v1/media-public，实际：" + prefixLines.get(0));
  }
}
