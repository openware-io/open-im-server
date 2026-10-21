# 工程标准目录

[中文](README.md) | [English](README.en.md)

> 状态：`current`（`develop/2.3.0`）  
> 标准正文以中文为准；本目录提供英文索引和一句话摘要。新增标准必须同时登记到本索引和根 README。
> 标准之间的层级、领域、门禁和缺口见[工程标准版图](STANDARDS_MAP.md)。

## 编号规则

标准编号是**分类块 + 两位序号**，不是按时间追加：

```text
00–09  总纲与治理
10–19  架构与服务设计
20–29  构建、数据与版本
30–39  交付与客户端
40–49  质量、可观测性与测试
50–59  安全与合规（预留）
60–69  运行与运维（预留）
```

- 编号在单个分类块内单调递增，不复用；
- 新增标准先选分类块，再取该块内下一个序号；
- 不允许无编号标准；
- 已发布编号不随意改号；确需调整时必须同步全仓链接和迁移记录。

## 00–09 总纲与治理

| 编号 | 标准 | 范围 |
| --- | --- | --- |
| 00 | [DDD 微服务软件工程规范纲要](00_DDD_MICROSERVICE_SOFTWARE_ENGINEERING_OUTLINE.md) | 服务拆分、边界、契约和工程治理总纲 |
| 01 | [文本编码与批量改写规范](01_TEXT_ENCODING_AND_BATCH_EDITING.md) | UTF-8、换行、批量编辑和校验 |
| 02 | [架构决策记录（ADR）规范](02_ARCHITECTURE_DECISION_RECORDS.md) | ADR 编号、状态、模板、索引与门禁 |
| 03 | [工程原则](03_ENGINEERING_PRINCIPLES.md) | 正确性、边界、单一事实源、自动化与可演进 |

## 10–19 架构与服务设计

| 编号 | 标准 | 范围 |
| --- | --- | --- |
| 10 | [DDD 服务工程规范](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) | 分层、包结构、命名、领域模型 |
| 12 | [RocketMQ 治理规范](12_ROCKETMQ_CONVENTIONS.md) | Topic、消息键、消费幂等、重试与死信 |
| 13 | [平台媒体规范](13_PLATFORM_MEDIA_CONVENTIONS.md) | 上传会话、对象存储、访问授权、生命周期 |

## 20–29 构建、数据与版本

| 编号 | 标准 | 范围 |
| --- | --- | --- |
| 20 | [Maven 工程规范](20_MAVEN_ENGINEERING_CONVENTIONS.md) | 模块、依赖、构建和版本 |
| 21 | [持久层技术栈标准](21_PERSISTENCE_STACK.md) | MyBatis-Plus、Flyway、数据访问边界 |
| 22 | [技术栈版本矩阵](22_TECHNOLOGY_STACK_VERSION_MATRIX.md) | Java、Spring Boot、数据库和中间件版本 |
| 23 | [数据库迁移脚本规范](23_DATABASE_MIGRATION_STANDARD.md) | Flyway 命名、不可变迁移与验证 |
| 24 | [币种规范](24_CURRENCY_CONVENTIONS.md) | 金额最小单位、币种字典、展示和结算快照 |

## 30–39 交付与客户端

| 编号 | 标准 | 范围 |
| --- | --- | --- |
| 30 | [客户端发布与版本治理规范](30_CLIENT_RELEASE_GOVERNANCE.md) | 版本号、构建号、渠道、灰度 |
| 31 | [客户端发布操作规范](31_CLIENT_RELEASE_SKILL.md) | 发布步骤、制品、验证和回滚 |

## 40–49 质量、可观测性与测试

| 编号 | 标准 | 范围 |
| --- | --- | --- |
| 40 | [可观测性规范](40_OBSERVABILITY_CONVENTIONS.md) | 日志、指标、追踪和告警 |
| 41 | [Java 日志规范](41_JAVA_LOGGING_CONVENTIONS.md) | 日志级别、字段、脱敏和采样 |
| 42 | [日志输出与诊断规范](42_LOGGING_AND_OUTPUTS.md) | 输出位置、诊断包和故障排查 |
| 43 | [E2E 测试与验收规范](43_E2E_TESTING_CONVENTIONS.md) | 冒烟、真实设备、验收和凭据注入 |

## 相关索引

- [文档中心](../../README.md)
- [工程规范](../governance/ENGINEERING_RULES.md)
- [门禁注册表](../governance/GATE_REGISTRY.md)
- [MQ 台账](../../30-reference/mq/REGISTRY.md)