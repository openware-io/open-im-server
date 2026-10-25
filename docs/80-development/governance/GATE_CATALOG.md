# 门禁目录

[中文](GATE_CATALOG.md) | [English](GATE_CATALOG.en.md)

> 状态：`current`（`develop/2.3.0`）
> 作用：按治理面组织 G01–G53；`GATE_REGISTRY.md` 是唯一编号台账，本目录补充类别、Owner 和阶段。
> 新增门禁流程见 [门禁治理规范](../standards/04_GATE_GOVERNANCE.md)。

## 分类说明

| 类别 | 关注面 | 典型 Owner |
| --- | --- | --- |
| `meta` | 门禁、标准、文档治理本身 | governance |
| `code-quality` | 编码、异常、日志、脚本卫生 | backend |
| `architecture` | 分层、边界、依赖、模块、路由 | architecture |
| `security` | 密钥、租户隔离、敏感数据 | security |
| `data` | 持久化、迁移、数据边界 | data |
| `domain` | 业务规则、消息语义、币种 | domain |
| `quality` | 可观测性、测试、验证清单 | quality |
| `media` | 媒体边界与 URL | platform |

## 元治理（门禁/标准/文档）

| ID | 门禁 | Category | Owner | Stage | 文档 / 证据 |
| --- | --- | --- | --- | --- | --- |
| G29 | documentation-links | meta | governance | docs | [门禁治理](../standards/04_GATE_GOVERNANCE.md) |
| G30 | standards-governance | meta | governance | docs | [门禁治理](../standards/04_GATE_GOVERNANCE.md) |
| G33 | gate-registry | meta | governance | static | [门禁治理](../standards/04_GATE_GOVERNANCE.md) |
| G44 | documentation-governance | meta | governance | docs | [门禁治理](../standards/04_GATE_GOVERNANCE.md) |
| G48 | standards-catalog | meta | governance | docs | [门禁治理](../standards/04_GATE_GOVERNANCE.md) |
| G49 | standards-map | meta | governance | docs | [门禁治理](../standards/04_GATE_GOVERNANCE.md) |
| G50 | documentation-reachability | meta | governance | docs | [门禁治理](../standards/04_GATE_GOVERNANCE.md) |
| G51 | gate-catalog | meta | governance | static | [门禁治理](../standards/04_GATE_GOVERNANCE.md) |
| G52 | docs-impact | meta | governance | docs | [门禁治理](../standards/04_GATE_GOVERNANCE.md) |
| G53 | domain-map | meta | governance | static | [业务域分类](../standards/05_DOMAIN_CLASSIFICATION.md) |

## 代码质量

| ID | 门禁 | Category | Owner | Stage | 文档 / 证据 |
| --- | --- | --- | --- | --- | --- |
| G01 | text-encoding | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G02 | powershell-utf8-safety | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G03 | powershell-ascii-only | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G04 | java-comment-quality | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G05 | java-deprecated-api-usage | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G06 | java-exception-logging | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G07 | java-logging-conventions | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G08 | java-silent-catch | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G09 | java-fully-qualified-names | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G10 | exception-handling | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G31 | repository-hygiene | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |
| G46 | autowired-constructor | code-quality | backend | static | [工程规范](ENGINEERING_RULES.md) |

## 架构与边界

| ID | 门禁 | Category | Owner | Stage | 文档 / 证据 |
| --- | --- | --- | --- | --- | --- |
| G11 | maven-version-ownership | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G18 | service-api-boundaries | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G19 | controller-repository-boundary | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G20 | controller-transaction-boundary | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G21 | domain-model-purity | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G24 | port-contract | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G25 | po-containment | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G26 | service-dependency-graph | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G43 | service-layout | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G45 | controller-mapping-integrity | architecture | architecture | static | [DDD 规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) |
| G47 | architecture-decisions | architecture | architecture | docs | [ADR 规范](../standards/02_ARCHITECTURE_DECISION_RECORDS.md) |

## 安全

| ID | 门禁 | Category | Owner | Stage | 文档 / 证据 |
| --- | --- | --- | --- | --- | --- |
| G16 | secret-exposure | security | security | static | [安全文档](../../90-security/README.md) |
| G17 | security-and-flyway-configuration | security | security | static | [安全文档](../../90-security/README.md) |
| G32 | tenant-sql | security | security | static | [安全文档](../../90-security/README.md) |
| G37 | observability-redaction | security | security | static | [安全文档](../../90-security/README.md) |

## 数据与迁移

| ID | 门禁 | Category | Owner | Stage | 文档 / 证据 |
| --- | --- | --- | --- | --- | --- |
| G12 | persistence-dependency-boundaries | data | data | static | [持久层标准](../standards/21_PERSISTENCE_STACK.md) |
| G27 | flyway-conventions | data | data | static | [数据库迁移](../standards/23_DATABASE_MIGRATION_STANDARD.md) |
| G28 | flyway-immutability | data | data | static | [数据库迁移](../standards/23_DATABASE_MIGRATION_STANDARD.md) |

## 领域与业务

| ID | 门禁 | Category | Owner | Stage | 文档 / 证据 |
| --- | --- | --- | --- | --- | --- |
| G13 | currency-literals | domain | domain | static | [领域文档](../../40-domain/README.md) |
| G14 | rocketmq-contracts | domain | domain | static | [领域文档](../../40-domain/README.md) |
| G15 | mq-registry | domain | domain | static | [MQ 台账](../../30-reference/mq/REGISTRY.md) |
| G22 | audit-fields | domain | domain | static | [领域文档](../../40-domain/README.md) |
| G23 | reserved-identifiers | domain | domain | static | [领域文档](../../40-domain/README.md) |
| G34 | currency-symbols | domain | domain | static | [币种规范](../standards/24_CURRENCY_CONVENTIONS.md) |
| G35 | currency-defaults | domain | domain | integration | [币种规范](../standards/24_CURRENCY_CONVENTIONS.md) |
| G42 | mq-semantics | domain | domain | integration | [MQ 台账](../../30-reference/mq/REGISTRY.md) |

## 质量与可观测性

| ID | 门禁 | Category | Owner | Stage | 文档 / 证据 |
| --- | --- | --- | --- | --- | --- |
| G38 | observability-correlation | quality | quality | integration | [可观测性](../standards/40_OBSERVABILITY_CONVENTIONS.md) |
| G39 | verify-inventory | quality | quality | docs | [E2E 规范](../standards/43_E2E_TESTING_CONVENTIONS.md) |
| G41 | observability-metrics | quality | quality | static | [可观测性](../standards/40_OBSERVABILITY_CONVENTIONS.md) |
| G54 | frontend-ui-standard | quality | quality | static | [前端 UI 风格](../standards/32_FRONTEND_UI_STYLE.md) |

## 媒体

| ID | 门禁 | Category | Owner | Stage | 文档 / 证据 |
| --- | --- | --- | --- | --- | --- |
| G36 | media-boundaries | media | platform | static | [媒体规范](../standards/13_PLATFORM_MEDIA_CONVENTIONS.md) |
| G40 | media-url | media | platform | static | [媒体规范](../standards/13_PLATFORM_MEDIA_CONVENTIONS.md) |
