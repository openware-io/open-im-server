> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/renovation/NAMING_REMNANT_AUDIT.md

# 命名遗留排查报告（gv / wv / 旧产品名 / 库名漂移）

> 目的：回答"涉及 gv、wv 的还存在遗留吗，这也是要改的，排查各个环节还有没有遗漏的"。
> 方法：全仓逐环节扫描（排除 `target/`、`.git/`、`.outputs/`、`node_modules/`），并区分**生产代码 / 活文档 / 日期快照**。
> 结论：**生产代码里 `gv` 命名基本已清理干净；真正的债在"活文档与规范"，以及 3 处代码小残留。**

---

## 1. 结论速览（按"要不要改"分级）

| 级别 | 环节 | 实测 | 处置 |
| --- | --- | --- | --- |
| **P0 必须改** | **活文档/规范里的库名与产品名漂移** | `ENGINEERING_RULES.md:154-155` 写"默认库 `gv_im`、SaaS 库 `gv_saas`"；`standards/16` 写 `open-saas-admin`/`open-saas-mobile`/`open-chat-app`；`standards/43` 写 `open-im-server`/`open-chat-desktop`；`standards/15:141` 写绝对路径 `D:\projects\cnb\open-im-server` | **改**——它们是**长期有效**的规范文档，会把错误名字继续传播给新人；且 `standards/15` 的绝对路径本身就是规范禁止项 |
| **P1 代码残留** | `.env` 的 MinIO 桶名 | `MEDIA_PRIVATE_BUCKET=open-im-local-private`（1 处） | **改**（本地桶名，改前确认无既有数据依赖） |
| **P2 保留（历史事实）** | IM 开放平台迁移语句 | `V1__user_baseline.sql` 4 处 `gvchat://oauth/callback` → `openchat://oauth/callback` 的**迁移本身** | **不改**——它记录的是历史回调地址，改了会让迁移失效 |
| **P3 保留（日期快照）** | 日期化审计/盘点报告 | `currency-scope-inventory-2026-09-18.md` 140 处、`audit-2026-09-17-exceptions-and-i18n.md` 71 处、`docs/renovation` 223 处（含我本轮分析稿） | **不改**——历史留档，改了就是篡改证据 |
| **已干净** | 其余全部生产环节 | `k8s` `deploy` `scripts` `config` `sdk` `gateways` `common-services` `platform-services` `group-services` `portal` `.github` —— **均 0 处** | 无需处理 |

---

## 2. P0：活文档 / 规范里的名字与实际不符（**最该修**）

### 2.1 库名漂移

| 出处 | 文档写的 | 代码实际 | 判定 |
| --- | --- | --- | --- |
| `ENGINEERING_RULES.md:154` | 默认库名 `gv_im` | `im_server`（IM 侧）/ `open_im`（SaaS 侧） | ❌ 不符 |
| `ENGINEERING_RULES.md:155` | "SaaS 库名为 `gv_saas`" | `open_im`（11 个服务）| ❌ 不符 |
| 各服务 `application.yml` | — | `open_im` 11 处、`${MYSQL_DATABASE:open_im}` 2 处、`${AUDIT_DATABASE:open_audit}` 1 处、`im_server`（IM 侧） | — |

**注意**：`gv_audit` **只出现在日期快照里**（`deployment/ACK.md` 的执行记录写于 2026-09-19），**代码与当前部署实际都用 `open_audit`**（见 `application-audit-schema.yml`、`k8s/local/mysql-saas-init-job.yaml`、`k8s/ack/saas-common.yaml`）。所以**审计库名没有不一致**，是那份执行记录过时了。

**顺带发现的一处不一致（需口径）**：IM 侧库名是 **`im_server`**（中性名），SaaS 侧是 **`open_im`**（带产品名 `open-im`）。这意味着 **SaaS 库反而占了 `open_im` 这个名字**，而 IM 库用了通用名 `im_server`——与"业务线分层"直觉相反，值得统一。

### 2.2 产品/前端名漂移（活规范里）

| 出处 | 写的 | 问题 |
| --- | --- | --- |
| `standards/16_CURRENCY_CONVENTIONS.md:3-4,83` | `open-saas-admin`、`open-saas-mobile`、`open-chat-app` | 旧产品名；且该文件是**币种能力的唯一权威约定**，名字错了会长期传播 |
| `standards/43_E2E_TESTING_CONVENTIONS.md:3` | `open-im-server`、`open-chat-app`、`open-chat-desktop` | 同上，"适用对象"一篇就错 |
| `standards/15_CLIENT_RELEASE_SKILL.md:141` | `` `D:\projects\cnb\open-im-server` `` | **绝对路径**——`standards/10` 明确"不绑定仓库名/路径"；且它同时泄露开发者本机目录，并再次暴露旧名 |

### 2.3 前端/客户端的实际名字（供你确认映射）

代码侧目前是：`portal/`、以及 `open-im` 出现 **223 处**（生产代码）。旧名 `open-im-server`/`open-chat-app`/`open-chat-desktop`/`open-saas-admin`/`open-saas-mobile` 仅有文档引用。

> **待你给新旧名映射表**（如 `open-chat-app` → ?、`open-saas-admin` → ?），我才能批量改活文档里的名字。这也是为什么我先只做排查、没动文档。

---

## 3. 其他潜在不一致（本轮顺带排查）

| 项 | 实测 | 判定 |
| --- | --- | --- |
| `support_schema_history` | media 服务的 Flyway history 表名（生产代码 1 处） | ⚠️ 命名与 `support_media_*` 同源（历史前缀 `support`）；media 域早已改用 `media` 前缀，**history 表名是遗漏**——但按 DC-10，history 表改名会影响既有库 Flyway 状态，需单独评估 |
| `openchat://` / `gvchat://` | 13 处 / 3 处（生产代码，均在 user 迁移内） | **保留**（迁移历史事实） |
| `flyway_schema_history_audit` | 审计服务 | **保留**（history 表名，非域表） |
| `IM_OAUTH_APP_SECRET` 等环境变量名 | 各服务 `application.yml` | 未见 `gv`/`wv` 残留 |

---

## 4. 尚未排查的环节（诚实说明）

以下环节本轮**未逐项扫描**，建议后续补：

1. **前端仓库/目录**（`portal/`、以及是否有独立的 App/PC 客户端仓库）——本仓只有 `portal`；若客户端在别的仓库，本报告覆盖不到。
2. **CNB/CI 流水线配置**（`.cnb.yml`）与制品命名（镜像 tag 前缀 `ghcr.io/openware-io/*` 已确认无 `gv`）。
3. **k8s 资源对象名**（Deployment/Service/ConfigMap 的**对象名**，而非其内容）——本轮扫的是文件内容。
4. **数据库既有数据**（bucket 名、库名若已存在实际数据，改名需迁移方案）。
5. **`docs/` 下除 standards/business/deployment 之外的活文档**——本轮按"日期快照 vs 活文档"粗分，个别边界可能需复核。

---

## 5. 建议执行顺序

| 步 | 动作 | 前置 |
| --- | --- | --- |
| 1 | 你给出**新旧名映射表**（前端/客户端/库名） | **需要你** |
| 2 | 改 `ENGINEERING_RULES.md:154-155` 的库名，使其与代码一致（或反之，若你决定统一改名） | 依赖步 1 |
| 3 | 改 `standards/16`、`standards/43`、`standards/15` 的旧产品名与绝对路径 | 依赖步 1 |
| 4 | 改 `.env` 的 `MEDIA_PRIVATE_BUCKET` | 确认无既有数据依赖 |
| 5 | 单独评估 `support_schema_history` 是否改名（涉及 Flyway 状态） | 需架构确认 |

> ⚠️ 本轮**未改动任何文件**——因为改名字需要你的新旧名映射，且改动会横跨规范与代码，属于架构口径变更。
