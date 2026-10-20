# IDE 配置建议（VS Code / Trae）

## 目标

- 使 IDE 对 `pom.xml` 的诊断结果与 Maven 实际构建结果一致。
- 避免 Spring Boot Tools 在本项目版本矩阵下产生大量“版本验证”提示，影响问题面板可读性。

## VS Code / Trae Workspace Settings

将以下配置写入工作区 `.vscode/settings.json`。如果没有该文件，请创建。此配置不会影响 Maven 构建，只影响 IDE 的问题面板提示。

```json
{
  "java.configuration.updateBuildConfiguration": "automatic",
  "boot-java.validation.java.version-validation": "OFF",
  "spring-boot.ls.problem.version-validation.UNSUPPORTED_OSS_VERSION": "IGNORE",
  "spring-boot.ls.problem.version-validation.UNSUPPORTED_COMMERCIAL_VERSION": "IGNORE",
  "spring-boot.ls.problem.version-validation.UPDATE_LATEST_MAJOR_VERSION": "IGNORE",
  "spring-boot.ls.problem.version-validation.UPDATE_LATEST_MINOR_VERSION": "IGNORE",
  "spring-boot.ls.problem.version-validation.UPDATE_LATEST_PATCH_VERSION": "IGNORE",
  "spring-boot.ls.problem.boot2.EXTRACT_REQUEST_MAPPING_PARENT_PATH": "IGNORE"
}
```

## IDE 提示与编译告警的区别

- `EXTEND_REQUEST_MAPPING_PARENT_PATH` / `EXTRACT_REQUEST_MAPPING_PARENT_PATH` 是 Spring Boot Tools 的**结构风格提示**，不是 `javac` 编译告警，也不会让 `mvn` 构建失败。
- 它建议把多个方法共享的路径前缀提升到类级 `@RequestMapping`。是否提取取决于可读性，不强制；涉及 `{id}` 等路径变量时，提升后反而可能降低可读性。
- 真实的重复路由、类路径重复和方法路径重复由 G45 `validate-controller-mapping-integrity.ps1` 检查。
- 本地把该提示设为 `IGNORE`，让 Problems 面板聚焦真正的编译/门禁问题；如果团队决定统一提取，再单独改造控制器，不要用 IDE 提示代替门禁。

## 重新导入与刷新建议

- 修改 `pom.xml` 后，若问题面板仍显示旧版本提示，执行一次 Maven 工程刷新（Reload Projects / Reimport）。
- 若仍存在不一致，清理 Java 语言服务工作区缓存并重启 IDE。
