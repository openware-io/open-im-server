# im-saas 文档整理与归档方案

[中文](DOCUMENTATION_ORGANIZATION.md) | [English](DOCUMENTATION_ORGANIZATION.en.md)

> 状态：`current`（全局方案，执行前基线）  
> 版本：`develop/2.3.0`  
> 原则：先做全局分类和归档规则，再逐篇修改；归档不等于删除，任何移动都可追溯、可回链。

## 1. 目标

把当前 249 个 Markdown 整理成一套可维护的文档体系：

1. **一个主题只有一个权威文档**，避免 README、服务说明、renovation 各写一份；
2. **现行与历史分离**，历史文档不污染当前契约；
3. **顶层双语、服务/标准逐步双语**；
4. **目录层级与工程拓扑一致**：入门 → 架构 → 部署 → 服务 → 业务/标准 → 历史；
5. **每次移动都有清单、链接和校验**。

## 2. 分类模型

### 2.1 生命周期

| 状态 | 含义 | 处理 |
| --- | --- | --- |
| `current` | 当前有效，代码/部署/流程仍依赖 | 保留并持续更新 |
| `draft` | 正在编写，尚未成为契约 | 保留，标注负责人和截止时间 |
| `historical` | 已完成或已被替代，仅作背景 | 归档，保留原文 |
| `superseded` | 有明确替代文档 | 归档，写 `superseded-by` |

### 2.2 处置动作

| 动作 | 定义 | 示例 |
| --- | --- | --- |
| **KEEP** | 内容正确、位置合理 | 工程拓扑、架构总览 |
| **UPDATE** | 位置合理但信息过期/不完整 | README 入口、部署文档、服务说明 |
| **MERGE** | 多份文档描述同一主题，合并到权威文档 | `im-services/**` 与服务目录重复 |
| **ARCHIVE** | 保留原文，移到 `docs/archive/`，加历史元数据 | renovation、superpowers、旧审计 |
| **DELETE** | 无独立内容、无入链、无历史价值；删除前必须确认 | 空壳、生成物、重复索引 |
| **ADD** | 当前体系缺失的文档 | 仓库英文 README、归档索引、服务契约模板 |

## 3. 现状盘点

统计口径：2026-10-19，`develop/2.3.0` 基线。

| 区域 | 数量 | 主要问题 |
| --- | ---: | --- |
| `docs/` 顶层 | 18 | 现行规范与一次性审计混放 |
| `docs/architecture/` | 2 | 新建拓扑；缺文档治理 |
| `docs/adr/` | 1 | 已撤回 ADR 仍留在现行 ADR 目录 |
| `docs/business/` | 5 | 与 renovation 业务方案重复 |
| `docs/compliance/` | 2 | 需与安全/隐私文档建立关系 |
| `docs/contracts/` | 2 | 快照与 OpenAPI 说明偏薄 |
| `docs/deployment/` | 7 | 端口、环境、发布说明有旧值 |
| `docs/features/` | 3 | 需确认是否仍现行 |
| `docs/im-services/` | 10 | 与服务目录职责重叠，命名 `im-` 但含 SaaS 服务 |
| `docs/mq/` | 1 | 需并入消息/标准或保留为专题 |
| `docs/archive/renovation/` | 78 | 大量历史改造记录，含仍有效的业务结论 |
| `docs/security/` | 2 | 需与合规、认证文档建立关系 |
| `docs/services/` | 8 | 服务树不完整，缺平台/公共能力 |
| `docs/standards/` | 15 | 现行规范，需逐步双语 |
| `docs/superpowers/` | 11 | 历史设计与计划快照 |
| 其他产品仓库 | 84 | README 无英文版，部分发布说明过期 |

## 4. 全局处置矩阵

### 4.1 保留并更新（KEEP / UPDATE）

| 文档/目录 | 处置 | 目标 |
| --- | --- | --- |
| 根 `README.md` / `README.en.md` | UPDATE | 一键部署、入口、账号、拓扑入口保持最新 |
| `docs/README.md` / `.en.md` | UPDATE | 文档中心与层级索引 |
| `docs/ARCHITECTURE.md` / `.en.md` | UPDATE | 运行时边界、分层职责 |
| `docs/architecture/TOPOLOGY.*` | KEEP/UPDATE | 工程拓扑唯一来源 |
| `docs/ENGINEERING_RULES.md` | KEEP | 工程规则 |
| `docs/GATE_REGISTRY.md` | KEEP/UPDATE | 门禁与检查项 |
| `docs/RELEASE_RUNBOOK.md` | UPDATE | 发布、回滚、版本追溯 |
| `docs/PROTOCOL.md` | KEEP | WebSocket/协议契约 |
| `docs/API_DOCUMENTATION.md` | UPDATE | 网关 Swagger/OpenAPI 入口 |
| `docs/PUSH_SETUP.md`、`docs/IDE_SETUP.md` | UPDATE | 开发环境与推送配置 |
| `docs/DATABASE_MIGRATION_STANDARD.md` | KEEP | 迁移规范 |
| `docs/standards/**` | UPDATE | 现行标准；逐步双语 |
| `docs/business/**` | UPDATE | 业务语义；吸收 renovation 中仍有效的结论 |
| `docs/deployment/**` | UPDATE | 统一 30080 入口、环境矩阵、发布步骤 |
| `docs/services/**` | UPDATE/ADD | 统一服务树，补齐平台/公共能力 |
| `docs/contracts/**`、`docs/security/**`、`docs/compliance/**` | UPDATE | 契约、安全、合规的现行说明 |
| `docs/features/**` | UPDATE | 保留仍现行功能说明，过期功能归档 |
| 各产品仓库 `README.md` | UPDATE/ADD | 补 `README.en.md`，统一快速开始 |

### 4.2 合并（MERGE）

| 来源 | 合并目标 | 规则 |
| --- | --- | --- |
| `docs/im-services/**`（10） | `docs/services/<service>/` | 先比对内容；保留权威说明，旧文档归档 |
| `docs/deployment/LOCAL.md` 与 Docker/K8s 说明 | `docs/deployment/` 单一入口 | 快速开始留在 README，细节留在部署文档 |
| `docs/architecture/ARCHITECTURE.md` 与拓扑 | 架构总览 + 工程拓扑 | 拓扑讲层次，架构讲运行时边界，不重复服务清单 |
| `docs/business/**` 与 renovation 中 KTV/钱包/隐私方案 | `docs/business/` + `docs/services/` | 先提取现行结论，再归档原文 |
| 根 README 的旧路由/服务清单 | 网关配置 + 服务目录 | 路由以 `application.yml` 为准 |

### 4.3 归档（ARCHIVE）

| 目录/文档 | 归档理由 | 目标位置 |
| --- | --- | --- |
| `docs/archive/renovation/**`（78） | 改造过程与历史决策；部分结论仍需提取 | `docs/archive/renovation/` |
| `docs/superpowers/**`（11） | 历史计划/设计快照 | `docs/archive/superpowers/` |
| `docs/audit-2026-09-17-exceptions-and-i18n.md` | 一次性审计快照 | `docs/archive/audits/2026-09-17/` |
| `docs/currency-scope-inventory-2026-09-18.md` | 一次性盘点 | `docs/archive/inventories/2026-09-18/` |
| `docs/DATABASE_AUDIT_REPORT.md` | 历史数据库审计 | `docs/archive/audits/`（现行结论并入 `standards/21` 或部署文档） |
| `docs/adr/0007-version-named-exceptions.md` | 已撤回 ADR | 保留在 `docs/adr/`，标 `superseded`，不移动 |
| 旧发布说明/一次性验收记录 | 只对当时版本有效 | `docs/archive/release-notes/` |

归档前必须做**结论提取**：把仍然有效的字段、接口、业务规则写进现行 `business/`、`services/` 或 `standards/`，不能把“历史方案”直接当现行契约。

### 4.4 清理/删除（DELETE）

只允许删除满足全部条件的文件：

1. 无独立内容（空壳、纯链接、重复索引）；
2. 无任何入链（`git grep` + 文档链接校验确认）；
3. 无历史价值（不是决策记录、审计证据或迁移依据）；
4. 已有权威替代文档，且归档索引已登记。

当前优先复核对象：

- `docs/contracts/README.md`：不是空壳，保留并补充契约索引；
- `docs/API_DOCUMENTATION.md`：保留并更新为网关 Swagger 说明；
- `docs/services/im-user-service/README.md`：保留并补齐职责/接口/数据；
- `docs/adr/0007-version-named-exceptions.md`：保留为 `superseded` ADR，不删除；
- 各仓库旧的发布/验收记录：先归档，不直接删除。

### 4.5 新增（ADD）

| 新增文档 | 目的 | 优先级 |
| --- | --- | --- |
| `docs/DOCUMENTATION_ORGANIZATION.md` / `.en.md` | 全局整理与归档方案（本文） | P0 |
| `docs/archive/README.md` / `.en.md` | 归档索引与元数据规则 | P0 |
| 各产品仓库 `README.en.md`（7 个） | 仓库入口双语 | P1 |
| `docs/services/` 服务模板与平台/公共能力说明 | 统一服务树 | P1 |
| `docs/deployment/ENVIRONMENTS.md` | 环境矩阵（Kind/Compose/ACK/生产） | P1 |
| `docs/architecture/DECISIONS.md` | ADR 索引与替代关系 | P2 |
| 文档元数据规范与 CI 门禁 | 状态、双语配对、旧名/链接检查 | P2 |

## 5. 目标目录结构

```text
docs/
├─ README.md / README.en.md
├─ 00-getting-started/
├─ 10-product/
├─ 20-architecture/
│  ├─ OVERVIEW.md / .en.md
│  ├─ TOPOLOGY.zh.md / TOPOLOGY.en.md
│  └─ adr/
├─ 30-reference/
│  ├─ API_DOCUMENTATION.md
│  ├─ PROTOCOL.md
│  ├─ mq/REGISTRY.md
│  └─ contracts/
├─ 40-domain/
│  ├─ README.md / README.en.md
│  └─ features/
├─ 50-services/
│  ├─ README.md / README.en.md
│  ├─ edge/
│  ├─ im/
│  ├─ platform/
│  └─ common/
├─ 60-deployment/
├─ 70-operations/
├─ 80-development/
│  ├─ standards/
│  ├─ governance/
│  ├─ tooling/
│  └─ contributing/
├─ 90-security/ + 91-compliance/
├─ assets/（资源目录，不编号）
└─ 99-archive/
   ├─ README.md / README.en.md
   ├─ audits/
   ├─ inventories/
   ├─ renovation/
   ├─ superpowers/
   └─ release-notes/
```

顶层只保留文档入口；架构、参考、领域、服务、交付、开发治理、安全合规、历史分别进入对应目录。历史目录不再与现行目录平级混放。

## 6. 归档规范

### 6.1 元数据头

每份归档文档顶部统一加：

```markdown
> Status: historical
> Archived: 2026-10-19
> Superseded-by: <现行文档相对路径，可为空>
> Original-location: <原路径>
```

ADR 用 `Status: superseded`，保留原决策内容，不重写。

### 6.2 命名与链接

- 保留原文件名，避免历史引用断裂；
- 必须用 `git mv` 移动，保留历史；
- 归档后在高流量旧路径保留**转发说明**（3 行以内），指向新位置；
- 同步更新所有入链，并运行 `validate-documentation-links.ps1`；
- 归档索引逐条登记：原路径、新路径、状态、替代文档。

### 6.3 删除门槛

```text
无独立内容 + 无入链 + 无历史价值 + 有权威替代 + 已登记归档索引
```

五项全部满足才允许删除；否则一律归档。

## 7. 执行顺序

### Phase 0：全局方案（本文）

- 定分类、归档规则、目标结构；
- 不再逐篇修改内容。

### Phase 1：归档脚手架与清单

- 建 `docs/archive/` 索引；
- 生成逐文件处置清单（CSV/Markdown）：路径、动作、目标、理由、负责人；
- 对 `renovation`、`superpowers`、旧审计生成**结论提取清单**；
- 先不移动文件。

### Phase 2：历史归档

- 按批次 `git mv` 到 archive；
- 加元数据头；
- 更新索引与入链；
- 每批跑链接校验。

### Phase 3：现行文档合并/更新

- 合并 `im-services` → `services`；
- 统一部署入口/环境矩阵；
- 更新路由、版本、品牌、账号说明；
- 补齐服务树。

### Phase 4：双语与新增

- 7 个仓库 `README.en.md`；
- 服务/标准/业务文档按优先级补英文；
- 补环境矩阵、ADR 索引、契约索引。

### Phase 5：清理与门禁

- 只删除满足五项门槛的文件；
- 加文档元数据/双语配对/旧名/链接 CI 检查；
- 归档索引与现行索引互相可达。

## 8. 验收标准

- 顶层目录不再混放历史审计/renovation；
- 一个服务只有一个权威说明；
- 所有历史文档有 `Status: historical` 和来源/替代关系；
- 现行文档不再出现 A380/WV Chat，旧端口/旧 URL 不作为入口；
- 顶层文档中英双语可切换；
- 文档链接校验、标准索引校验通过；
- 从 README 三跳内可到达任一现行主题。

## 9. 非目标

- 本方案不一次性重写 249 个文档；
- 不删除历史决策和审计证据；
- 不把历史文档改写成“看起来最新”的文档；
- 不在没有逐文件清单的情况下批量移动。
