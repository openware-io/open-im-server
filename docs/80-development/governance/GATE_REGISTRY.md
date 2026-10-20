# 门禁注册表

本表是 `scripts/validate/validate-*.ps1` 的**唯一编号索引**。编号是稳定注册 ID，不是文件名前缀：
脚本文件名保持稳定，避免重命名破坏 `invoke-engineering-validation.ps1`、文档链接与历史记录。

维护规则：

1. 新增、删除或改名门禁脚本，必须同步更新本表；
2. 新增门禁必须同时接入 `invoke-engineering-validation.ps1`；
3. `模式` 必须与 `report-only-gates.txt` 一致：文件中有条目 = report-only，否则 = blocking；
4. `validate-gate-registry.ps1` 会校验“脚本、注册表、invoke、report-only”四方一致，缺一即失败。

| ID | 门禁 | 脚本 | 模式 | 说明 |
| --- | --- | --- | --- | --- |
| G01 | text-encoding | validate-text-encoding.ps1 | blocking | UTF-8 编码与乱码检测 |
| G02 | powershell-utf8-safety | validate-powershell-utf8-safety.ps1 | blocking | PowerShell 读写 UTF-8 的安全性 |
| G03 | powershell-ascii-only | validate-powershell-ascii-only.ps1 | blocking | 校验脚本保持 ASCII |
| G04 | java-comment-quality | validate-java-comment-quality.ps1 | blocking | Java 注释质量 |
| G05 | java-deprecated-api-usage | validate-java-deprecated-api-usage.ps1 | blocking | 已废弃 API 使用 |
| G06 | java-exception-logging | validate-java-exception-logging.ps1 | blocking | 异常日志完整性 |
| G07 | java-logging-conventions | validate-java-logging-conventions.ps1 | blocking | 日志规范 |
| G08 | java-silent-catch | validate-java-silent-catch.ps1 | blocking | 静默吞异常 |
| G09 | java-fully-qualified-names | validate-java-fully-qualified-names.ps1 | blocking | 禁止全限定名 |
| G10 | exception-handling | validate-exception-handling.ps1 | blocking | 异常处理边界 |
| G11 | maven-version-ownership | validate-maven-version-ownership.ps1 | blocking | Maven 版本归属 |
| G12 | persistence-dependency-boundaries | validate-persistence-dependency-boundaries.ps1 | blocking | 持久层依赖边界 |
| G13 | currency-literals | validate-currency-literals.ps1 | blocking | 币种字面量扫描 |
| G14 | rocketmq-contracts | validate-rocketmq-contracts.ps1 | blocking | RocketMQ 契约 |
| G15 | mq-registry | validate-mq-registry.ps1 | blocking | MQ 台账 |
| G16 | secret-exposure | validate-secret-exposure.ps1 | blocking | 密钥暴露 |
| G17 | security-and-flyway-configuration | validate-security-and-flyway-configuration.ps1 | blocking | 安全与 Flyway 配置 |
| G18 | service-api-boundaries | validate-service-api-boundaries.ps1 | blocking | 服务 API 边界 |
| G19 | controller-repository-boundary | validate-controller-repository-boundary.ps1 | blocking | 控制器不得持有仓储 |
| G20 | controller-transaction-boundary | validate-controller-transaction-boundary.ps1 | blocking | 控制器不得持有事务边界 |
| G21 | domain-model-purity | validate-domain-model-purity.ps1 | blocking | 领域模型纯净度 |
| G22 | audit-fields | validate-audit-fields.ps1 | blocking | 审计字段不进领域模型 |
| G23 | reserved-identifiers | validate-reserved-identifiers.ps1 | blocking | `version` 等保留字段名 |
| G24 | port-contract | validate-port-contract.ps1 | blocking | 仓储端口契约 |
| G25 | po-containment | validate-po-containment.ps1 | blocking | PO 不越出持久化层 |
| G26 | service-dependency-graph | validate-service-dependency-graph.ps1 | blocking | 服务依赖图 |
| G27 | flyway-conventions | validate-flyway-conventions.ps1 | blocking | Flyway 脚本规范 |
| G28 | flyway-immutability | validate-flyway-immutability.ps1 | blocking | 已执行迁移不可回改 |
| G29 | documentation-links | validate-documentation-links.ps1 | blocking | 文档链接与索引 |
| G30 | standards-governance | validate-standards-governance.ps1 | blocking | 规范治理 |
| G31 | repository-hygiene | validate-repository-hygiene.ps1 | blocking | 仓库卫生 |
| G32 | tenant-sql | validate-tenant-sql.ps1 | blocking | 租户 SQL 隔离 |
| G33 | gate-registry | validate-gate-registry.ps1 | blocking | 本注册表与脚本/invoke/report-only 一致性 |
| G34 | currency-symbols | validate-currency-symbols.ps1 | blocking | 符号/中文名/币种映射只能来自 Currency 枚举 |
| G35 | currency-defaults | validate-currency-defaults.ps1 | blocking | 缺省 USD、门店写穿、历史单据币种快照由测试锁定 |
| G36 | media-boundaries | validate-media-boundaries.ps1 | blocking | 无代理上传、SDK 不越界、内部端点不外泄、媒体日志脱敏 |
| G37 | observability-redaction | validate-observability-redaction.ps1 | blocking | 日志不得直接打印 token/password/secret/signature |
| G38 | observability-correlation | validate-observability-correlation.ps1 | blocking | HTTP → MQ → 消费 → 定时任务的关联标识续接 |
| G39 | verify-inventory | validate-verify-inventory.ps1 | blocking | scripts/verify 与 43 章 §3 清单一致 |
| G40 | media-url | validate-media-url.ps1 | blocking | 公共媒体仅经 /api/v1/media-public/**，无旧路径/re-write |
| G41 | observability-metrics | validate-observability-metrics.ps1 | blocking | 指标名唯一、禁止高基数标签 |
| G42 | mq-semantics | validate-mq-semantics.ps1 | blocking | MQ 台账顺序键/幂等键必须真实，消息链路用 conversationId |
| G43 | service-layout | validate-service-layout.ps1 | blocking | Service 内部目录固定模板、controller 位置与遗留包 allowlist |
| G44 | documentation-governance | validate-documentation-governance.ps1 | blocking | 归档元数据、双语配对、旧命名与文档资产治理 |
| G45 | controller-mapping-integrity | validate-controller-mapping-integrity.ps1 | blocking | 控制器路由重复、类路径重复与 consumes 重载 |
| G46 | autowired-constructor | validate-autowired-constructor.ps1 | blocking | 单构造器禁止不必要的 @Autowired |
| G47 | architecture-decisions | validate-architecture-decisions.ps1 | blocking | ADR 目录、索引、模板、编号、章节与状态 |
