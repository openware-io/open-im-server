# 日志输出与诊断规范

## 目录规范

项目生成的日志和临时诊断产物不得写入仓库根目录。

在项目根目录下使用以下结构：

```text
.outputs/
 logs/
  build/
   YYYYMMDD/
    <task>-<timestamp>.log
  im-services/
   gateway/
    application.log
    application.YYYY-MM-DD.<index>.log.gz
   im-user-service/
   im-message-service/
   im-conversation-service/
   im-admin-service/
   im-access-ws/
```

## 规则

1. 构建、校验、打包和部署命令日志写入 `.outputs/logs/build/`。
2. 服务运行日志写入 `.outputs/logs/im-services/<service-name>/`。
3. Maven 构建产物保留在各模块的 `target/` 目录，除非发布流程明确复制到其他位置。
4. 发布管理器产物保留在 `release_manager/build_output/`。
5. 新增工作不得在根目录产生临时的 `build_*.log`、`deploy_*.log` 等文件。

## 适用边界

本规范只约束构建、验证、发布和运行期间的日志文件、诊断产物及其目录归档。代码中的日志声明、文案、级别和异常处理遵循 [41 Java 日志规范](./41_JAVA_LOGGING_CONVENTIONS.md)；日志、指标、追踪、告警和审计的共同原则遵循 [40 可观测性规范](./40_OBSERVABILITY_CONVENTIONS.md)。

## 工具入口

- `run-dev.ps1` 是本地启动服务的标准入口，并预先配置每个服务的日志文件。
- `scripts/validate/invoke-engineering-validation.ps1` 是工程校验和 Maven 命令执行的标准入口，会持久化构建日志。

## 排障指引

1. 先在 `.outputs/logs/build/<date>/` 定位对应构建或部署任务的日志。
2. 运行期问题检查 `.outputs/logs/im-services/<service-name>/application.log`。
3. 服务名必须与 `spring.application.name` 保持一致，以便将日志准确映射到模块和部署单元。
