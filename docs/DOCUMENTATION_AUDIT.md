# im-saas 工程文档审计（2026-10-19）

[中文](DOCUMENTATION_AUDIT.md) | [English](DOCUMENTATION_AUDIT.en.md)

> 状态：`current`  
> 版本：`develop/2.3.0` 文档整改（基线 tag `2.2.1`）  
> 方法：对工作区 249 个 Markdown 文件做命名、版本、链接、服务清单和语言覆盖检查；对运行中的 Kind 部署做服务清单核对。

## 1. 结论

文档的主要问题不是“缺文件”，而是**缺少统一的工程级结构**：

- 仓库 README、服务说明、业务方案、改造记录各自演进；
- 架构文档只覆盖 IM 域，SaaS 平台、公共能力、客户端和基础设施没有进入同一张拓扑；
- 现行文档曾混用旧命名 A380 / WV Chat；
- 文档以中文为主，没有明确的双语边界；
- 版本、路由、服务清单存在漂移。

本次先建立“文档中心 + 工程拓扑 + 审计清单 + 顶层双语”，其余按 Phase 2/3 分批处理。

## 1.1 执行进度（2026-10-19）

| 阶段 | 状态 | 结果 |
| --- | --- | --- |
| Phase 0 全局方案 | ✅ | 分类、归档规则、目标目录、执行顺序 |
| Phase 1 处置清单 | ✅ | 349 条逐文件清单，覆盖 8 个 im-saas 仓库 |
| Phase 2 历史归档 | ✅ | 94 份历史文档归档；renovation 78、superpowers 11、审计/盘点/旧发布说明 |
| Phase 3 合并与更新 | 🟡 | `im-services` 10 份已合并进服务树；入口/端口/服务名持续更新 |
| Phase 4 双语与新增 | 🟡 | 7 个仓库补英文 README；服务/业务/标准已补英文索引；逐篇翻译待补 |
| Phase 5 清理与门禁 | 🟡 | 新增文档治理门禁；删除仅按五项门槛执行 |

剩余工作集中在：逐篇服务/标准/业务文档更新、旧仓库指南清理、少量历史文档的结论提取。

## 2. 问题清单

### P0：拓扑结构缺失

1. `docs/ARCHITECTURE.md` 只列 `gateway`、4 个 IM 服务和 `im-access-ws`，没有 `platform-*`、`common-*`、`group-idaas`、SaaS H5/后台和客户端。
2. 同一文档仍声称 Flyway 只由 `im-user-service` / `im-message-service` 维护，实际已有 tenant、identity、admin、customer、resource、order、payment、media、audit 等多个域。
3. 根 README 的“当前服务拓扑”缺少 SaaS 平台域、公共能力域、数据基础设施和部署单元层次。
4. 根 README 仍列出 `im-order-service`，但当前仓库和 Kind 部署中不存在该服务；订单能力在 `platform-order-service`。

### P0：双语缺口

5. 241 个 Markdown 中，本次之前没有 `*.en.md` 顶层工程文档；中文占绝对多数。
6. 各仓库 README（8 个产品仓库）均未提供英文版本。
7. 服务说明、业务方案、标准文档没有统一语言切换约定。

### P1：命名与版本漂移

8. `A380` 在 50 个文档中出现 257 次，主要集中在 `docs/archive/renovation/`、历史审计和部分现行部署文档。
9. `WV Chat` 仍在 9 个文档中出现 15 次，包括桌面端 README、发布说明和发布手册产物名。
10. 文档中仍有 46 处 `2.2.0`，与当前阶段 tag `2.2.1` 不一致。
11. 现行服务板块文档已改为 OpenChat，但历史 renovation 文档保留旧名，需要标注“历史”而非直接改写。

### P1：路由、服务与数据说明漂移

12. 根 README 路由表缺少 `/miniapp/services`、`/business/**` 以及 `platform-*` 的公开路由。
13. 服务板块数据只写在 renovation 文档中，未进入架构/服务索引。
14. 数据归属表缺失 `open_saas`、`open_group`、`open_audit` 的完整边界说明。
15. 服务端口、部署单元和仓库的对应关系分散在多个文档中，没有单一拓扑来源。

### P2：文档组织与可维护性

16. `open-im-server/docs` 有 165 个 Markdown，历史审计、renovation、superpowers 计划与现行规范混放。
17. 缺少文档中心索引；根 README 的“文档索引”是平铺列表，不能表达层次。
18. 历史文档没有 `historical` / `superseded-by` 元数据，读者无法判断是否仍有效。
19. 没有 CI 文档门禁检查双语配对、旧命名和失效链接。

## 3. 分批计划

### Phase 1（已完成，2026-10-19）

- 新增 `docs/README.md` / `.en.md` 文档中心；
- 新增 `docs/architecture/TOPOLOGY.zh.md` / `.en.md` 分层拓扑；
- 新增本审计及英文版；
- 为 `docs/ARCHITECTURE.md` 补英文版和语言切换；
- 为根 README 补英文版和语言切换，修正服务拓扑与 `im-order-service` 等明显漂移。

### Phase 2

- 8 个产品仓库 README 增加 `README.en.md`；
- 优先为 `docs/services/**` 补英文摘要；
- 统一服务说明的“职责 / 接口 / 数据 / 依赖 / 运行入口”结构。

### Phase 3

- `docs/standards/**`、`docs/deployment/**`、`docs/business/**` 按优先级补双语；
- 部署文档中的旧命名、端口、Ingress 路径做一次全量核对；
- 服务板块、钱包品牌、B 端入口等近期变更同步到现行文档。

### Phase 4

- 将历史审计、renovation、superpowers 计划标记为 `historical`；
- 对已失效结论加 `superseded-by`；
- 统一 `A380` / `WV Chat` 只在历史文档中出现；
- 建立归档目录或归档索引。

### Phase 5

- 增加文档门禁：
  - 顶层文档必须成对存在 `.md` / `.en.md`；
  - 语言切换链接必须双向可达；
  - 现行文档不得出现 A380 / WV Chat；
  - 路由、服务名、表名引用必须能在代码/迁移中找到。

## 4. 验收标准

- 新读者从根 README 能在 3 跳内到达拓扑、部署、服务、标准和审计；
- 拓扑能回答“仓库 → 部署单元 → 服务 → 数据”的层次；
- 顶层文档中英双语可切换；
- 现行文档不再出现旧品牌；
- 历史文档有明确状态，不再被误认为现行契约；
- 文档门禁可在 CI 中执行。
