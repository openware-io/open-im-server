package com.gvchat.infrastructure.audit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 守卫测试：凡在 {@code src/main/java} 里引用 {@link AuditClient} 的 Spring Boot 应用模块，都必须在自己的
 * {@code src/main/resources/application.yml} 里显式声明 {@code app.audit-service.base-url}。
 *
 * <p>背景：{@link AuditClientConfig} 的缺省值是 {@code http://localhost:4190}（本地直跑/Compose 需要），
 * 集群内必须由各服务的 application.yml 覆盖成 {@code http://common-audit-service:4190}。历史上只有
 * platform-admin-service 配了该键，其余服务在 k8s/ACK 里静默退化成 localhost:4190，写审计只打 WARN
 * （{@code IllegalStateException: 写审计失败}）、记录丢失。
 *
 * <p>本用例只做静态文本扫描，不启动 Spring 上下文，因此放在 SDK 模块（AuditClient 的归属地）：
 * 任何依赖本 SDK 的服务模块用 {@code -am} 跑测试时都会执行到它，无需在业务模块里重复放置。
 */
class AuditBaseUrlConfiguredTest {

  /** 应用模块必须声明的配置键（application.yml 里的扁平化键名）。 */
  private static final String BASE_URL_KEY = "app.audit-service.base-url";

  /** 本地直跑/Compose 覆盖用的环境变量；仅出现该变量、未写扁平键也算等价配置。 */
  private static final String BASE_URL_ENV = "INTERNAL_AUDIT_SERVICE_BASE_URL";

  /** SDK 自身只提供缺省值，不是可部署的应用模块，不要求它配 application.yml。 */
  private static final String SDK_MODULE = "sdk/infrastructure";

  @Test
  void everyModuleUsingAuditClientConfiguresAuditBaseUrl() throws IOException {
    Path repoRoot = locateRepoRoot();
    Set<String> usingModules = findModulesReferencingAuditClient(repoRoot);

    // 扫描自检：AuditClient 自己所在的模块必须在结果里，否则说明仓库根定位或目录遍历失效，
    // 测试会「零模块通过」地虚假变绿。
    assertTrue(
        usingModules.contains(SDK_MODULE),
        "守卫测试的仓库根/遍历失效：" + repoRoot + " 下未扫描到 " + SDK_MODULE);

    List<String> violations = new ArrayList<>();
    for (String module : usingModules) {
      if (SDK_MODULE.equals(module)) {
        continue;
      }
      Path yml = repoRoot.resolve(module).resolve("src/main/resources/application.yml");
      if (!Files.isRegularFile(yml)) {
        violations.add(module + "：缺少 " + repoRoot.relativize(yml).toString().replace('\\', '/'));
        continue;
      }
      // 必须先去掉注释再判断：注释里出现键名/环境变量名不算配置，否则守卫会被注释「糊弄」过去。
      String config = stripComments(new String(Files.readAllBytes(yml), StandardCharsets.UTF_8));
      if (!config.contains(BASE_URL_KEY) && !config.contains(BASE_URL_ENV)) {
        violations.add(module + "：application.yml 未声明 " + BASE_URL_KEY);
      } else if (!config.contains(BASE_URL_ENV)) {
        violations.add(module + "：application.yml 未通过 " + BASE_URL_ENV + " 支持本地覆盖");
      }
    }

    if (!violations.isEmpty()) {
      fail(
          "引用 AuditClient 的应用模块必须显式配置审计上报地址（集群内用 common-audit-service 服务名，"
              + "本地用 "
              + BASE_URL_ENV
              + "=http://localhost:4190 覆盖），否则 k8s/ACK 会退化成 SDK 的 localhost 默认值、"
              + "审计静默丢失：\n  - "
              + String.join("\n  - ", violations));
    }
  }

  private static Path locateRepoRoot() {
    Path dir = Paths.get("").toAbsolutePath().normalize();
    for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
      if (Files.isRegularFile(
          candidate
              .resolve(SDK_MODULE)
              .resolve("src/main/java/com/gvchat/infrastructure/audit/AuditClient.java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "未能在 " + dir + " 的任一父目录下找到 " + SDK_MODULE + "/src/main/java/.../AuditClient.java");
  }

  /** 扫描仓库，返回「src/main/java 下引用 AuditClient 的模块目录」（相对仓库根、'/' 分隔）。 */
  private static Set<String> findModulesReferencingAuditClient(Path repoRoot) throws IOException {
    String root = repoRoot.toString().replace('\\', '/');
    Set<String> modules = new TreeSet<>();
    Files.walkFileTree(
        repoRoot,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (!dir.equals(repoRoot)) {
              String name = dir.getFileName().toString();
              if ("target".equals(name)
                  || ".git".equals(name)
                  || "node_modules".equals(name)
                  || ".idea".equals(name)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            String path = file.toString().replace('\\', '/');
            int marker = path.indexOf("/src/main/java/");
            if (marker < 0 || !path.endsWith(".java")) {
              return FileVisitResult.CONTINUE;
            }
            String content = new String(readBytesQuietly(file), StandardCharsets.UTF_8);
            if (content.contains("AuditClient")) {
              modules.add(path.substring(root.length() + 1, marker));
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return modules;
  }

  private static byte[] readBytesQuietly(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new IllegalStateException("读取源码失败: " + file, e);
    }
  }

  /** 去掉 YAML 行内注释，只保留真正的配置内容（本仓库的键/值里不含 '#'）。 */
  private static String stripComments(String yaml) {
    StringBuilder effective = new StringBuilder(yaml.length());
    for (String line : yaml.split("\n", -1)) {
      int hash = line.indexOf('#');
      effective.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
    }
    return effective.toString();
  }
}
