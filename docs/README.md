# im-saas 文档中心

[中文](README.md) | [English](README.en.md)

> 维护范围：`D:\projects\cnb-oss` 下的 im-saas 产品仓库与 `open-im-server` 工程文档  
> 当前阶段：`develop/2.3.0` 开发中（最近阶段 tag `2.2.1`）  
> 语言约定：面向工程决策和交付的顶层文档提供中英双语；历史审计/renovation 记录只做归档标注，不追写双语。

## 1. 文档层次

```text
im-saas 文档
├─ 00 入门
│  ├─ 一键部署
│  └─ 访问入口与账号规则
├─ 05 文档治理
│  ├─ 文档整理与归档方案
│  ├─ 文档审计（问题与计划）
│  └─ 归档索引
├─ 10 架构
│  ├─ 工程拓扑（仓库 / 部署单元 / 服务 / 数据）
│  ├─ 架构总览（运行时边界与职责）
│  └─ 工程规范（研发与门禁）
├─ 20 部署与运行
│  ├─ 本地部署
│  ├─ Docker Compose
│  ├─ Kubernetes / Kind
│  └─ 发布手册
├─ 30 服务
│  ├─ 接入层（gateway / im-access-ws）
│  ├─ IM 业务域
│  ├─ SaaS 平台域
│  └─ 公共能力域
├─ 40 业务
│  └─ 会话、隐私、KTV、订单等业务语义
├─ 50 标准
│  └─ DDD、RocketMQ、媒体、版本、币种、日志、测试
└─ 90 历史
   ├─ renovation（改造过程）
   ├─ superpowers（设计与计划快照）
   └─ audit（历史审计）
```

## 2. 阅读路径

| 目标 | 顺序 |
| --- | --- |
| 第一次上手 | 一键部署 → 访问入口/账号规则 → 工程拓扑 |
| 理解系统 | 工程拓扑 → 架构总览 → 服务说明 |
| 本地开发 | 本地部署 → 服务说明 → 工程规范 |
| 发布交付 | 发布手册 → 部署说明 → E2E 规范 |
| 追溯历史 | 文档审计 → renovation / audit（仅作背景，不作为现行契约） |

## 3. 分层索引

### 00 入门

| 文档 | 关系 |
| --- | --- |
| [一键部署](../README.md#一键部署推荐) | 起点：环境要求、Kind 一键部署、入口与验证 |

### 05 文档治理

| 文档 | 关系 |
| --- | --- |
| [文档整理与归档方案](DOCUMENTATION_ORGANIZATION.md) | 全局分类、处置动作、归档规则和执行顺序 |
| [文档审计](DOCUMENTATION_AUDIT.md) | 当前问题、优先级和分批计划 |
| [归档索引](archive/README.md) | 历史文档的登记、元数据和移动检查 |

### 10 架构与治理

| 文档 | 关系 |
| --- | --- |
| [工程拓扑](architecture/TOPOLOGY.zh.md) | 架构文档的顶层结构 |
| [架构总览](ARCHITECTURE.md) | 拓扑的运行时解释：边界、职责、数据归属 |
| [工程规范](ENGINEERING_RULES.md) | 约束研发、评审与门禁 |
| [门禁注册表](GATE_REGISTRY.md) | 工程规范的可执行检查清单 |

### 20 部署与运行

| 文档 | 关系 |
| --- | --- |
| [本地部署](deployment/LOCAL.md) | 三种本地方式的统一入口 |
| [Docker Compose](deployment/DOCKER.md) | 日常联调 |
| [Kubernetes 清单](../k8s/README.md) | Kind / ACK 资源与镜像 |
| [发布手册](RELEASE_RUNBOOK.md) | 发布、回滚、版本追溯 |
| [本地部署索引](deployment/README.md) | 部署文档的上级目录 |

### 30 服务与业务

| 文档 | 关系 |
| --- | --- |
| [服务说明](services/README.md) | 运行时服务目录与分层 |
| [Gateway](services/gateway/README.md) | 接入层入口 |
| [IM 用户服务](services/im-user-service/README.md) | IM 业务域 |
| [IM 消息服务](services/im-message-service/README.md) | IM 业务域 |
| [IM 会话服务](services/im-conversation-service/README.md) | IM 业务域 |
| [IM 管理服务](services/im-admin-service/README.md) | IM 业务域 |
| [平台管理 BFF](services/platform-admin-service/README.md) | SaaS 平台域 |
| [平台身份服务](services/platform-identity-service/README.md) | SaaS 平台域 |
| [租户服务](services/platform-tenant-service/README.md) | SaaS 平台域 |
| [客户服务](services/platform-customer-service/README.md) | SaaS 平台域 |
| [资源服务](services/platform-resource-service/README.md) | SaaS 平台域 |
| [营销服务](services/platform-marketing-service/README.md) | SaaS 平台域 |
| [媒体支撑](services/common-media-service/README.md) | 公共能力域 |
| [审计服务](services/common-audit-service/README.md) | 公共能力域 |
| [支付服务](services/common-payment-service/README.md) | 公共能力域 |
| [支付渠道](services/common-payment-channel-service/README.md) | 公共能力域 |
| [短信服务](services/common-sms-service/README.md) | 公共能力域 |
| [业务文档](business/README.md) | 业务语义，位于服务说明之上 |

### 50 标准

| 文档 | 关系 |
| --- | --- |
| [标准目录](standards/README.md) | 标准体系总纲与索引 |
| [10 DDD 服务工程规范](standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) | 服务实现规范 |
| [12 RocketMQ 规范](standards/12_ROCKETMQ_CONVENTIONS.md) | 消息与事件 |
| [13 平台媒体规范](standards/13_PLATFORM_MEDIA_CONVENTIONS.md) | 媒体与对象存储 |
| [14 客户端发布治理](standards/14_CLIENT_RELEASE_GOVERNANCE.md) | 客户端版本 |
| [15 客户端发布操作](standards/15_CLIENT_RELEASE_SKILL.md) | 发布操作 |
| [16 币种规范](standards/16_CURRENCY_CONVENTIONS.md) | 金额、代币与展示 |
| [20 Maven 工程规范](standards/20_MAVEN_ENGINEERING_CONVENTIONS.md) | 构建与依赖 |
| [21 持久层标准](standards/21_PERSISTENCE_STACK.md) | 数据访问 |
| [22 技术栈版本矩阵](standards/22_TECHNOLOGY_STACK_VERSION_MATRIX.md) | 版本基线 |
| [40 可观测性规范](standards/40_OBSERVABILITY_CONVENTIONS.md) | 日志、指标、追踪 |
| [43 E2E 测试规范](standards/43_E2E_TESTING_CONVENTIONS.md) | 验收与冒烟 |

### 90 历史归档

| 文档 | 关系 |
| --- | --- |
| [renovation](archive/renovation/) | 改造过程；仅作背景 |
| [superpowers](archive/superpowers/) | 设计与计划快照（已归档） |
| [历史审计](archive/audits/2026-09-17/audit-2026-09-17-exceptions-and-i18n.md) | 2026-09-17 审计快照（已归档） |

## 4. 语言与状态

- **现行文档**：`*.md` 为中文，配套 `*.en.md` 为英文；文件顶部提供语言切换。
- **历史文档**：保留原始中文，标题或索引标注“历史/归档”，不要求补英文。
- **状态标记**：`current`、`draft`、`historical`、`superseded`；新文档必须显式标注。
- **单一事实源**：运行时路由以 `gateways/gateway/src/main/resources/application.yml` 为准；数据表以 Flyway 迁移为准；本文档只做导航和解释。

## 5. 当前双语覆盖

| 层级 | 中文 | 英文 | 状态 |
| --- | --- | --- | --- |
| 文档中心 | `docs/README.md` | `docs/README.en.md` | ✅ |
| 工程拓扑 | `docs/architecture/TOPOLOGY.zh.md` | `docs/architecture/TOPOLOGY.en.md` | ✅ |
| 架构总览 | `docs/ARCHITECTURE.md` | `docs/ARCHITECTURE.en.md` | ✅ |
| 文档审计 | `docs/DOCUMENTATION_AUDIT.md` | `docs/DOCUMENTATION_AUDIT.en.md` | ✅ |
| 仓库 README | 各仓库 `README.md` | `README.en.md`（7 个产品仓库 + 本仓库） | ✅ |
| 服务/业务/标准 | 中文正文 | 英文索引（`services/README.en.md`、`business/README.en.md`、`standards/README.en.md`） | 🟡 逐篇翻译待补 |

## 6. 文档治理规则

1. 新增顶层文档必须同时提交中英文版本和语言切换链接。
2. 文档中的路由、服务名、表名必须能在代码或迁移中找到对应来源。
3. 历史文档不直接改写成新事实；需要新结论时新增 `superseded-by` 说明。
4. 过期命名（A380、WV Chat 等）只允许出现在历史/归档文档中；现行文档必须使用 OpenChat。
5. 文档变更必须通过链接校验；涉及拓扑、路由、数据归属的变更必须同步更新本索引和拓扑文档。
6. 提交前运行：

```powershell
.\scripts\validate\validate-documentation-links.ps1
.\scripts\validate\validate-documentation-governance.ps1
.\scripts\validate\validate-standards-governance.ps1
```

CI 在 `.github/workflows/docs-governance.yml` 中执行同样的检查。
