# im-saas 文档中心

[中文](README.md) | [English](README.en.md)

> 维护范围：`D:\projects\cnb-oss` 下的 im-saas 产品仓库与 `open-im-server` 工程文档  
> 当前阶段：`main` / 2.4.1 开发迭代中（当前无发布 tag）  
> 语言约定：面向工程决策和交付的顶层文档提供中英双语；历史审计/renovation 记录只做归档标注，不追写双语。
> 常用入口：[门禁清单](80-development/governance/GATE_REGISTRY.md) · [门禁目录](80-development/governance/GATE_CATALOG.md) · [工程标准版图](80-development/standards/STANDARDS_MAP.md) · [架构图集](20-architecture/diagrams/README.md) · [部署索引](60-deployment/README.md)

## 1. 文档结构

```text
docs/
├─ README.md / README.en.md
├─ 00-getting-started/      # 5 分钟上手
├─ 10-product/              # 产品定位、能力、截图
├─ 20-architecture/         # 拓扑、架构总览、ADR
├─ 30-reference/            # API、协议、MQ 台账、OpenAPI 契约
├─ 40-domain/               # 领域语义、业务规则、功能专题
├─ 50-services/             # 服务说明：edge / im / platform / common
├─ 60-deployment/           # 本地、Docker、K8s、推送配置
├─ 70-operations/           # 发布、升级、回滚、运维
├─ 80-development/          # 贡献者入口
│  ├─ standards/            # 强制工程规范
│  ├─ governance/           # 工程规则、门禁、文档治理
│  ├─ tooling/              # IDE 等开发工具配置
│  └─ contributing/         # 贡献流程
├─ 90-security/             # 安全设计、敏感数据、遗留风险
├─ 91-compliance/           # 合规与整改
├─ assets/                  # 截图、图、Logo（资源目录，不编号）
└─ 99-archive/              # 历史文档，仅作背景
```

## 2. 阅读路径

| 目标 | 顺序 |
| --- | --- |
| 第一次上手 | 产品介绍 → 快速开始 → 一键部署 → 访问入口/账号规则 |
| 理解系统 | 架构总览 → 工程拓扑 → 服务说明 |
| 二次开发 | 领域文档 → 服务说明 → 工程规范 |
| 部署运维 | 本地部署 → Docker/K8s → 发布手册 |
| 参与贡献 | 贡献指南 → 门禁注册表 → DDD 规范 |
| 追溯历史 | 归档索引 → renovation / audit（仅作背景） |

## 3. 分层索引

### 入门与产品

| 文档 | 关系 |
| --- | --- |
| [快速开始](00-getting-started/README.md) | 5 分钟跑起来的最短路径 |
| [一键部署](../README.md#一键部署推荐) | 环境要求、Kind 一键部署、入口与验证 |
| [OpenChat 产品介绍](10-product/README.md) | 项目定位、产品界面、能力矩阵、适用场景 |
| [权限目录矩阵（产品说明）](10-product/PERMISSION_MATRIX.md) | 四态矩阵、筛选与角色列、越界治理提示、数据新鲜度 |

### 架构与参考

| 文档 | 关系 |
| --- | --- |
| [架构总览](20-architecture/OVERVIEW.md) | 运行时边界、职责、数据归属 |
| [工程拓扑](20-architecture/TOPOLOGY.zh.md) | 仓库 / 部署单元 / 服务 / 数据 |
| [架构图集](20-architecture/diagrams/README.md) | 8 张核心架构图：上下文、容器、部署、数据、上下文、时序、MQ、状态机 |
| [参考索引](30-reference/README.md) | API、协议、契约与 MQ 台账的统一入口 |
| [API 文档](30-reference/API_DOCUMENTATION.md) | 网关 Swagger/OpenAPI 入口 |
| [协议说明](30-reference/PROTOCOL.md) | WebSocket 事件与 REST 约定 |
| [OpenAPI 契约](30-reference/contracts/README.md) | 跨服务/对外接口契约 |
| [客户接口受众](30-reference/CUSTOMER_AUDIENCE.md) | saas-c / saas-b 严格受众 |
| [酒店预订客户关联](30-reference/SAAS_BOOKING_CUSTOMER_LINK.md) | 后台手机号自动解析 accountId |
| [MQ 台账](30-reference/mq/REGISTRY.md) | Topic / 事件登记 |
| [ADR 索引](20-architecture/adr/README.md) | 架构决策记录、状态与模板 |
| [ADR 0007](20-architecture/adr/0007-version-named-exceptions.md) | 已撤回的 version 字段名决策 |

### 领域与服务

| 文档 | 关系 |
| --- | --- |
| [领域文档](40-domain/README.md) | 会话、隐私、KTV、订单等业务语义 |
| [服务目录](50-services/README.md) | 运行时服务目录与分层 |
| [接入层服务](50-services/edge/README.md) | gateway / im-access-ws |
| [IM 业务域服务](50-services/im/README.md) | 用户、消息、会话、管理 |
| [SaaS 平台域服务](50-services/platform/README.md) | 管理 BFF、身份、租户、客户、资源、营销 |
| [公共能力域服务](50-services/common/README.md) | 媒体、审计、支付、支付渠道、短信 |

### 交付与运维

| 文档 | 关系 |
| --- | --- |
| [本地部署](60-deployment/LOCAL.md) | 三种本地方式的统一入口 |
| [SaaS 部署品牌约定](60-deployment/SAAS_BRANDING_DEPLOYMENT.md) | OpenChat=IM，SaaS 租户/酒店/钱包命名 |
| [部署索引](60-deployment/README.md) | 部署、ACK、客户端发布与环境配置 |
| [Docker Compose](60-deployment/DOCKER.md) | 日常联调 |
| [Kubernetes 清单](../k8s/README.md) | Kind / ACK 资源与镜像 |
| [推送配置](60-deployment/PUSH_SETUP.md) | 推送通道与环境配置 |
| [发布手册](70-operations/RELEASE_RUNBOOK.md) | 发布、回滚、版本追溯 |

### 贡献者文档

| 文档 | 关系 |
| --- | --- |
| [参与贡献](80-development/contributing/README.md) | 提交前检查与协作流程 |
| [2.4.1 本地现状交接](80-development/HANDOFF_2.4.1.md) | 菜单权限修复、账号、部署与门禁交接 |
| [2.4.1 严格合规审计](80-development/AUDIT_2.4.1_STRICT.md) | 架构/DB/文档/门禁审查结论与整改项 |
| [工程规范](80-development/governance/ENGINEERING_RULES.md) | 研发、评审与门禁规则 |
| [工程规范审计](80-development/governance/ENGINEERING_STANDARDS_AUDIT_2026-10-26.md) | 本轮 A380/SaaS 规范审查结论 |
| [工程仓库与分支](80-development/governance/ENGINEERING_REPOSITORIES.md) | 全工程仓库范围、统一版本分支与一致性检查 |
| [门禁注册表](80-development/governance/GATE_REGISTRY.md) | 可执行检查清单 |
| [门禁目录](80-development/governance/GATE_CATALOG.md) | 类别、Owner、阶段与证据 |
| [门禁治理规范](80-development/standards/04_GATE_GOVERNANCE.md) | 新增、分类、评审与退役规则 |
| [IDE 配置](80-development/tooling/IDE_SETUP.md) | VS Code / Trae 诊断与 JDK 配置 |
| [AI 协作原则](80-development/governance/AI_COLLABORATION_PRINCIPLES.md) | 人机协作边界 |
| [文档整理与归档方案](80-development/governance/DOCUMENTATION_ORGANIZATION.md) | 分类、归档规则和执行顺序 |
| [标准目录](80-development/standards/README.md) | 按分类块浏览标准 |
| [工程标准版图](80-development/standards/STANDARDS_MAP.md) | 层级、领域、门禁与覆盖缺口 |
| [工程原则](80-development/standards/03_ENGINEERING_PRINCIPLES.md) | L0 判断依据与取舍原则 |
| [门禁治理](80-development/standards/04_GATE_GOVERNANCE.md) | 门禁分类、登记、生命周期与文档影响 |
| [数据库迁移标准](80-development/standards/23_DATABASE_MIGRATION_STANDARD.md) | Flyway 与数据迁移 |
| [ADR 规范](80-development/standards/02_ARCHITECTURE_DECISION_RECORDS.md) | 决策记录的编号、状态、模板与门禁 |

### 安全与合规

| 文档 | 关系 |
| --- | --- |
| [安全文档](90-security/README.md) | 敏感数据保护、延后安全发现 |
| [合规文档](91-compliance/README.md) | 中国区合规、P0 整改 |
| [文档审计](99-archive/audits/DOCUMENTATION_AUDIT.md) | 历史审计快照，已归档 |

### 历史归档

| 文档 | 关系 |
| --- | --- |
| [归档索引](99-archive/README.md) | 历史文档登记、元数据和移动检查 |
| [renovation](99-archive/renovation/) | 改造过程；仅作背景 |
| [superpowers](99-archive/superpowers/) | 设计与计划快照（已归档） |

## 4. 语言与状态

- **现行文档**：`*.md` 为中文，配套 `*.en.md` 为英文；文件顶部提供语言切换。
- **历史文档**：保留原始中文，标题或索引标注“历史/归档”，不要求补英文。
- **状态标记**：`current`、`draft`、`historical`、`superseded`；新文档必须显式标注。
- **单一事实源**：运行时路由以 `gateways/gateway/src/main/resources/application.yml` 为准；数据表以 Flyway 迁移为准；本文档只做导航和解释。

## 5. 文档治理规则

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