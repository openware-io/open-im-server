package io.openware.im.accessws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GatewayArchitectureBaselineTest {
  private static final Path PROJECT_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
  private static final Path MODULE_ROOT = PROJECT_ROOT.resolve("gateways/im-access-ws");

  @Test
  void pomMustNotDirectlyDeclareOrExcludePersistenceDependencies() throws IOException {
    String pom = Files.readString(MODULE_ROOT.resolve("pom.xml"));

    assertFalse(pom.contains("<dependency><groupId>com.baomidou</groupId>"));
    assertFalse(pom.contains("<dependency><groupId>org.mybatis</groupId>"));
    assertFalse(pom.contains("<dependency><groupId>com.mysql</groupId>"));
    assertFalse(pom.contains("maven-compiler-plugin"));
    assertFalse(pom.contains("<exclusions>"));
    assertFalse(pom.contains("<artifactId>mybatis-plus-annotation</artifactId>"));
    assertFalse(pom.contains("<artifactId>mybatis-spring</artifactId>"));
  }

  @Test
  void gatewayMustKeepItsOnlyWebSocketEntryPoint() throws IOException {
    String config = Files.readString(MODULE_ROOT.resolve(
        "src/main/java/io/openware/im/accessws/config/WebSocketConfig.java"));

    assertTrue(config.contains("\"/ws/im/v1\""));
  }

  @Test
  void applicationMustRestrictComponentScanningToTheGatewayPackage() throws IOException {
    String application = Files.readString(MODULE_ROOT.resolve(
        "src/main/java/io/openware/im/accessws/ImAccessWsApplication.java"));

    assertFalse(application.contains("scanBasePackages = \"io.openware.im\""));
    assertTrue(application.contains("SnowflakeIdGenerator.class"));
  }

  @Test
  void gatewayConfigurationMustRequireExplicitSecurityValues() throws IOException {
    String applicationYaml = Files.readString(MODULE_ROOT.resolve("src/main/resources/application.yml"));

    assertTrue(applicationYaml.contains("password: ${REDIS_PASSWORD:}"));
    assertTrue(applicationYaml.contains("secret: ${JWT_SECRET:}"));
    assertTrue(applicationYaml.contains("allowed-origins: ${IM_ACCESS_WS_ALLOWED_ORIGINS:}"));
    assertFalse(applicationYaml.contains("default-secret"));
    assertFalse(applicationYaml.contains("password: ${REDIS_PASSWORD:123456}"));
  }

  @Test
  void inboundHandlerMustNotDependOnLegacyBusinessServices() throws IOException {
    String handler = Files.readString(MODULE_ROOT.resolve(
        "src/main/java/io/openware/im/accessws/handler/ImWebSocketHandler.java"));

    assertFalse(handler.contains("io.openware.im.domain."));
    assertFalse(handler.contains("ImChatPermissionService"));
  }

  @Test
  void gatewayMustNotContainLegacyBusinessLayersOrGroupRoomRouting() throws IOException {
    Path sourceRoot = MODULE_ROOT.resolve("src/main/java/io/openware/im");
    String pom = Files.readString(MODULE_ROOT.resolve("pom.xml"));

    assertFalse(Files.exists(sourceRoot.resolve("common")));
    assertFalse(Files.exists(sourceRoot.resolve("domain")));
    assertFalse(Files.exists(sourceRoot.resolve("infrastructure")));
    assertFalse(Files.exists(sourceRoot.resolve("accessws/adapter")));
    assertFalse(Files.readString(sourceRoot.resolve("accessws/message/StoredMessageEventListener.java"))
        .contains("sendToGroup"));
    assertFalse(pom.contains("<dependency><groupId>com.baomidou</groupId>"));
    assertFalse(pom.contains("<dependency><groupId>org.mybatis</groupId>"));
    assertFalse(pom.contains("<dependency><groupId>com.mysql</groupId>"));
    assertFalse(pom.contains("spring-boot-starter-data-jpa"));
  }
}
