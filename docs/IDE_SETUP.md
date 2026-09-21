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
  "spring-boot.ls.problem.version-validation.UPDATE_LATEST_PATCH_VERSION": "IGNORE"
}
```

## 重新导入与刷新建议

- 修改 `pom.xml` 后，若问题面板仍显示旧版本提示，执行一次 Maven 工程刷新（Reload Projects / Reimport）。
- 若仍存在不一致，清理 Java 语言服务工作区缓存并重启 IDE。
