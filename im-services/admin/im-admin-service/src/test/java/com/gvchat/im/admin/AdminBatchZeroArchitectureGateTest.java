package com.gvchat.im.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AdminBatchZeroArchitectureGateTest {
  private static final Path PROJECT_ROOT = Path.of("..", "..", "..").toAbsolutePath().normalize();
  private static final Path MODULE_ROOT = PROJECT_ROOT.resolve("im-services/admin/im-admin-service");
  private static final Path SOURCE_ROOT = MODULE_ROOT.resolve("src/main/java");
  private static final List<String> DOMAIN_FORBIDDEN_DEPENDENCIES = List.of(
      "org.springframework", "com.baomidou", "org.apache.ibatis", "com.fasterxml.jackson",
      "org.springframework.data.redis", "org.apache.rocketmq", "org.springframework.web.client",
      "java.net.http", "java.net.HttpURLConnection");
  private static final List<String> API_MODULE_FORBIDDEN_DEPENDENCIES = List.of(
      "org.springframework.boot", "com.baomidou", "org.apache.ibatis", "org.flywaydb", ".service.");
  private static final List<String> CROSS_DOMAIN_MAPPERS = List.of(
      "UserRepository.java", "FriendRepository.java", "MessageRepository.java", "GroupRepository.java",
      "DeviceTokenRepository.java", "UserStickerRepository.java");

  @Test
  void legacyManagementDomainMustBeRemoved() throws IOException {
    List<Path> legacyDomainSources = javaSources(SOURCE_ROOT.resolve("com/gvchat/im/domain"));

    for (Path source : legacyDomainSources) {
      String name = source.getFileName().toString();
      assertFalse(List.of("SystemConfig.java", "AppRelease.java", "Report.java", "Violation.java",
          "SensitiveWord.java", "MiniappServiceType.java", "MiniappServiceItem.java",
          "SystemConfigRepository.java", "AppReleaseRepository.java", "ReportRepository.java",
          "ViolationRepository.java", "SensitiveWordRepository.java", "MiniappServiceTypeRepository.java",
          "MiniappServiceItemRepository.java", "AdminConfigService.java", "AppConfigService.java",
          "AppReleaseService.java", "ReportService.java", "AdminSecurityService.java",
          "MiniappServiceTypeService.java", "MiniappServiceItemService.java").contains(name), source.toString());
    }
  }

  @Test
  void newAdminDomainMustRemainFreeOfFrameworkAndInfrastructureDependencies() throws IOException {
    Path adminDomain = SOURCE_ROOT.resolve("com/gvchat/im/admin/domain");
    List<Path> domainSources = Files.exists(adminDomain) ? javaSources(adminDomain) : List.of();

    for (Path source : domainSources) {
      String content = Files.readString(source);
      for (String forbiddenDependency : DOMAIN_FORBIDDEN_DEPENDENCIES) {
        assertFalse(content.contains(forbiddenDependency), source + ": " + forbiddenDependency);
      }
    }
  }

  @Test
  void newAdminApiMustExposeOnlyApiDtos() throws IOException {
    Path adminApi = SOURCE_ROOT.resolve("com/gvchat/im/admin/api");
    List<Path> apiSources = Files.exists(adminApi) ? javaSources(adminApi) : List.of();

    for (Path source : apiSources) {
      String content = Files.readString(source);
      assertFalse(content.contains("com.gvchat.im.domain.entity"), source.toString());
      assertFalse(content.contains("com.gvchat.im.admin.infra.persistence.po"), source.toString());
      assertFalse(content.contains("com.gvchat.im.domain.repository"), source.toString());
    }
  }

  @Test
  void apiModuleMustRemainAnEmptyFrameworkFreeArtifact() throws IOException {
    Path apiModule = PROJECT_ROOT.resolve("im-services/admin/im-admin-api");
    String pom = Files.readString(apiModule.resolve("pom.xml"));

    assertEquals(List.of(), javaSources(apiModule.resolve("src/main/java")));
    for (String forbiddenDependency : API_MODULE_FORBIDDEN_DEPENDENCIES) {
      assertFalse(pom.contains(forbiddenDependency), forbiddenDependency);
    }
  }

  @Test
  void directCrossDomainMappersMustNotRemainInAdmin() throws IOException {
    Path repositoryRoot = SOURCE_ROOT.resolve("com/gvchat/im/domain/repository");
    List<String> actual = javaSources(repositoryRoot).stream()
        .map(path -> path.getFileName().toString())
        .filter(CROSS_DOMAIN_MAPPERS::contains)
        .sorted()
        .toList();

    assertEquals(List.of(), actual);
  }

  @Test
  void crossDomainControllerModelsMustNotRemainInAdmin() throws IOException {
    Path controllerRoot = SOURCE_ROOT.resolve("com/gvchat/im/admin/controller");
    List<String> actual = javaSources(controllerRoot).stream()
        .filter(source -> {
          try {
            return Files.readString(source).contains("com.gvchat.im.domain.entity");
          } catch (IOException exception) {
            throw new IllegalStateException(exception);
          }
        })
        .map(source -> source.getFileName().toString())
        .sorted()
        .toList();

    List<String> crossDomainControllers = List.of(
        "AdminDeviceTokenController.java", "AdminFriendController.java", "AdminMessageController.java",
        "AdminUserController.java", "AdminUserStickerController.java");
    assertEquals(List.of(), actual.stream().filter(crossDomainControllers::contains).toList());
  }

  @Test
  void adminMustNotReturnRawPushTokens() throws IOException {
    String deviceResponse = Files.readString(PROJECT_ROOT.resolve(
        "im-services/user/im-user-api/src/main/java/com/gvchat/im/user/api/admin/AdminDeviceTokenResponse.java"));
    String adminQueryService = Files.readString(PROJECT_ROOT.resolve(
        "im-services/user/im-user-service/src/main/java/com/gvchat/im/user/application/admin/AdminUserQueryApplicationService.java"));

    assertFalse(deviceResponse.contains("private final String token;"));
    assertTrue(deviceResponse.contains("tokenFingerprint"));
    assertTrue(adminQueryService.contains("tokenFingerprint(token.getToken())"));
  }

  @Test
  void httpCompatibilityMatrixMustCoverAllPublishedRouteFamilies() throws IOException {
    String matrix = Files.readString(PROJECT_ROOT.resolve(
        "docs/renovation/ADMIN_READONLY_02_COMPATIBILITY.md"));

    for (String routeFamily : List.of(
        "/admin/users", "/admin/friends", "/admin/messages", "/admin/points/accounts",
        "/admin/config", "/admin/client-releases", "/admin/miniapp/service-types",
        "/admin/miniapp/services", "/admin/security", "/admin/monitor", "/admin/stats",
        "/client/release-check", "/config/client", "/miniapp/services", "/reports")) {
      assertTrue(matrix.contains(routeFamily), routeFamily);
    }
    assertTrue(matrix.contains("`ADMIN`"));
    assertTrue(matrix.contains("`GlobalExceptionHandler`"));
  }

  @Test
  void startupMustScanOnlyAdminBusinessPackages() throws IOException {
    String application = Files.readString(SOURCE_ROOT.resolve(
        "com/gvchat/im/admin/ImAdminServiceApplication.java"));

    assertFalse(application.contains("scanBasePackages = \"com.gvchat.im\""));
    assertTrue(application.contains("\"com.gvchat.im.admin\""));
    assertFalse(application.contains("\"com.gvchat.im.domain\""));
    assertTrue(application.contains("AdminPersistenceConfig.class"));
    assertTrue(application.contains("RedisConfig.class"));
    assertTrue(application.contains("MqInfrastructureConfig.class"));
    assertTrue(application.contains("SecurityConfig.class"));
  }

  @Test
  void configurationMustRequireExplicitInfrastructureAndSecurityValues() throws IOException {
    String configuration = Files.readString(MODULE_ROOT.resolve("src/main/resources/application.yml"));
    String compose = Files.readString(PROJECT_ROOT.resolve("docker-compose.yml"));

    assertTrue(configuration.contains("url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_DATABASE:${DB_NAME:}}"));
    assertTrue(configuration.contains("username: ${DB_USERNAME:${DB_USER:}}"));
    assertTrue(configuration.contains("password: ${DB_PASSWORD:}"));
    assertTrue(configuration.contains("host: ${REDIS_HOST:}"));
    assertTrue(configuration.contains("port: ${REDIS_PORT:}"));
    assertTrue(configuration.contains("database: ${REDIS_DB:}"));
    assertTrue(configuration.contains("password: ${REDIS_PASSWORD:}"));
    assertTrue(configuration.contains("secret: ${JWT_SECRET:}"));
    assertTrue(configuration.contains("base-url: ${INTERNAL_USER_SERVICE_BASE_URL:}"));
    assertTrue(configuration.contains("message-base-url: ${INTERNAL_MESSAGE_SERVICE_BASE_URL:}"));
    assertTrue(configuration.contains("conversation-base-url: ${INTERNAL_CONVERSATION_SERVICE_BASE_URL:}"));
    assertFalse(configuration.contains("default-secret"));
    assertFalse(configuration.contains("DB_PASSWORD:root"));
    assertFalse(configuration.contains("DB_USER:root"));
    assertFalse(configuration.contains("localhost:3100"));
    assertTrue(compose.contains("DB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD must be set}"));
    assertTrue(compose.contains("REDIS_PASSWORD: ${REDIS_PASSWORD:?REDIS_PASSWORD must be set}"));
    assertTrue(compose.contains("JWT_SECRET: ${JWT_SECRET:?JWT_SECRET must be set}"));
    assertTrue(compose.contains(
        "INTERNAL_SERVICE_AUTH_SECRET: ${INTERNAL_SERVICE_AUTH_SECRET:?INTERNAL_SERVICE_AUTH_SECRET must be set}"));
    assertTrue(compose.contains("INTERNAL_USER_SERVICE_BASE_URL: http://im-user-service:3100"));
    assertTrue(compose.contains("INTERNAL_MESSAGE_SERVICE_BASE_URL: http://im-message-service:3200"));
    assertTrue(compose.contains("INTERNAL_CONVERSATION_SERVICE_BASE_URL: http://im-conversation-service:3300"));
    assertFalse(compose.contains("JWT_SECRET: ${JWT_SECRET:-change-me-in-production}"));
  }

  @Test
  void adminManagementFactsMustUseDedicatedAdmTablesAndFlywayMigrations() throws IOException {
    String configuration = Files.readString(MODULE_ROOT.resolve("src/main/resources/application.yml"));
    String baseline = Files.readString(MODULE_ROOT.resolve(
        "src/main/resources/db/migration/V1__adm_management_domain_baseline.sql"));
    assertTrue(configuration.contains("enabled: true"));
    assertTrue(configuration.contains("baseline-version: 0"));
    for (String table : List.of("adm_system_config", "adm_app_release", "adm_report", "adm_violation",
        "adm_sensitive_word", "adm_miniapp_service_type", "adm_miniapp_service_item")) {
      assertTrue(baseline.contains(table), table);
    }
    assertFalse(Files.exists(MODULE_ROOT.resolve(
        "src/main/resources/db/migration/V2__backfill_adm_management_domain.sql")));
    assertTrue(Files.exists(MODULE_ROOT.resolve(
        "src/main/resources/db/migration/V2__replace_app_release_governance.sql")));
  }

  @Test
  void legacyManagementEntitiesMustNotRemainBoundToSharedTables() throws IOException {
    for (String legacyTable : List.of("system_configs", "app_releases", "reports", "violations",
        "sensitive_words", "miniapp_service_types", "miniapp_services")) {
      List<Path> legacyManagementEntities = javaSources(SOURCE_ROOT.resolve("com/gvchat/im/domain/entity")).stream()
          .filter(source -> {
            try {
              return Files.readString(source).contains("@TableName(\"" + legacyTable + "\")");
            } catch (IOException exception) {
              throw new IllegalStateException(exception);
            }
          }).toList();
      assertTrue(legacyManagementEntities.isEmpty(), legacyTable);
    }
  }

  private List<Path> javaSources(Path root) throws IOException {
    if (!Files.exists(root)) {
      return List.of();
    }
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
  }
}
