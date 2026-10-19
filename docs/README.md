# im-saas 文档中心

[中文](README.md) | [English](README.en.md)

> 维护范围：`D:\projects\cnb-oss` 下的 im-saas 产品仓库与 `open-im-server` 工程文档  
> 当前阶段：2.2.1（2026-10-19）  
> 语言约定：面向工程决策和交付的顶层文档提供中英双语；历史审计/renovation 记录只做归档标注，不追写双语。

## 1. 从这里开始

| 文档 | 用途 |
| --- | --- |
| [一键部署](../README.md#一键部署推荐) | 环境要求、Kind 一键部署、访问与验证 |
| [工程拓扑](architecture/TOPOLOGY.zh.md) | 仓库、部署单元、服务、数据的分层结构 |
| [架构总览](ARCHITECTURE.md) | 运行时边界、服务职责与关键约束 |
| [文档审计](DOCUMENTATION_AUDIT.md) | 本次排查的问题、优先级和分批计划 |
| [本地部署](deployment/LOCAL.md) | Kind / Docker Compose / 本地进程 |
| [工程规范](ENGINEERING_RULES.md) | 研发、评审与门禁规则 |
| [发布手册](RELEASE_RUNBOOK.md) | 发布、回滚与版本追溯 |
| [服务说明](services/) | 各运行时服务的职责与接口 |
| [业务文档](business/) | 会话、隐私、KTV 等业务语义 |
| [标准](standards/) | DDD、RocketMQ、媒体、版本、币种等规范 |
| [历史方案](renovation/) | 改造过程记录；默认视为历史，不作为现行契约 |

## 2. 语言与状态

- **现行文档**：`*.md` 为中文，配套 `*.en.md` 为英文；文件顶部提供语言切换。
- **历史文档**：保留原始中文，标题或索引标注“历史/归档”，不要求补英文。
- **状态标记**：`current`、`draft`、`historical`、`superseded`；新文档必须显式标注。
- **单一事实源**：运行时路由以 `gateways/gateway/src/main/resources/application.yml` 为准；数据表以 Flyway 迁移为准；本文档只做导航和解释。

## 3. 当前双语覆盖

| 层级 | 中文 | 英文 | 状态 |
| --- | --- | --- | --- |
| 文档中心 | `docs/README.md` | `docs/README.en.md` | ✅ |
| 工程拓扑 | `docs/architecture/TOPOLOGY.zh.md` | `docs/architecture/TOPOLOGY.en.md` | ✅ |
| 架构总览 | `docs/ARCHITECTURE.md` | `docs/ARCHITECTURE.en.md` | ✅ |
| 文档审计 | `docs/DOCUMENTATION_AUDIT.md` | `docs/DOCUMENTATION_AUDIT.en.md` | ✅ |
| 仓库 README | 各仓库 `README.md` | 待补 `README.en.md` | ⏳ Phase 2 |
| 服务/业务/标准 | 中文为主 | 待补 | ⏳ Phase 3 |

## 4. 文档治理规则

1. 新增顶层文档必须同时提交中英文版本和语言切换链接。
2. 文档中的路由、服务名、表名必须能在代码或迁移中找到对应来源。
3. 历史文档不直接改写成新事实；需要新结论时新增 `superseded-by` 说明。
4. 过期命名（A380、WV Chat 等）只允许出现在历史/归档文档中；现行文档必须使用 OpenChat。
5. 文档变更必须通过链接校验；涉及拓扑、路由、数据归属的变更必须同步更新本索引和拓扑文档。
