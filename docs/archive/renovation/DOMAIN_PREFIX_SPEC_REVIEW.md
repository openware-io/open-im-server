> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/renovation/DOMAIN_PREFIX_SPEC_REVIEW.md

# 领域与表前缀规范：现状梳理 + 偏差 + 待决策建议

> 用途：供你决策的完整材料。**本文件不改动任何代码或规范**；`ENGINEERING_RULES.md` 与 `scripts/validate/table-prefixes.txt` 均未修改。
>
> 数据来源：`docs/ENGINEERING_RULES.md:152-185`、`docs/renovation/SAAS_PLATFORM_09_SERVICE.md:19-32`、各服务 `src/main/resources/db/migration*/`、以及各 PO 的 `@TableName`。下表中所有表名与数量均为本会话实测。

---

## 一、现行规范（原文要点）

| 出处 | 规定 |
| --- | --- |
| `ENGINEERING_RULES.md:154` | 当前阶段所有表允许放在单库；默认库名 `gv_im` |
| `:155` | 运行时分三类域：IM（`im-services/`）、平台/SaaS（`platform-services/`）、通用支撑（`common-services/`）；**IM 与 SaaS 数据库独立**（SaaS 库 `gv_saas`）；SaaS 内部多租户用**共享库 + 行级租户隔离** |
| `:156` | "表名统一使用**已登记领域缩写前缀 + 单数业务名词**；缩写目的在控制长度，但必须**语义稳定、全局唯一**，**禁止按开发人员偏好临时创造**" |
| `:158` | 设有 **"领域与数据库表前缀登记表"**（6 列：领域 / 领域职责 / 数据库表前缀 / 状态 / 示例） |
| `:182` | "新领域、新前缀或前缀语义变更**必须先完成架构确认**，并在上表及对应领域设计中登记后才能创建 Flyway 表；**既有表不得仅为统一缩写而重命名**" |
| `:183-184` | 表名/列名 `snake_case`；表名单数、业务主体在前 |

**门禁实现**（`scripts/validate/validate-flyway-conventions.ps1`）：
- **L167**：建表前缀必须已登记在 `table-prefixes.txt`。
- **L63-65**：每个已登记前缀必须出现在 `ENGINEERING_RULES.md`（正则 `` `prefix_?` ``，**只要出现即可**）。
- ⚠️ 二者耦合：**登记要两处同改**；移除时须**先删文档行、再删清单行**，否则报 `registered prefix ... is not documented`。
- ⚠️ **门禁查不出"前缀归谁"**——这是下面所有偏差能长期存在的原因。

---

## 二、领域规范（设计文档口径，权威）

`SAAS_PLATFORM_09_SERVICE.md:19-28` 的限界上下文表（SaaS 侧）：

| 服务域 | 限界上下文 | 不拥有 |
| --- | --- | --- |
| `identity` | Platform Identity & Entry：SaaS 平台账号、登录、凭据、OAuth/第三方登录、资料同步、账号绑定 | IM 聊天数据、租户角色、订单/支付/会员事实 |
| `tenant` | Tenant Access：租户、组织、门店、商户主体、**角色和作用域授权** | 订单、客户消费、支付交易、资源占用 |
| `resource` | Resource Scheduling：资源、排班、时段占用 | 订单金额、支付、会员权益 |
| `order` | Transaction Fulfillment：预约、订单、明细、KTV 履约 | 支付渠道交易、押金余额、角色定义 |
| `payment` | Funds Collection：支付、退款、押金、班次、日结 | 商品定价、订单履约字段、会员等级 |
| `customer` | Tenant Loyalty：租户客户、会员、积分、权益、储值 | 平台积分、平台营销、订单价格计算 |
| `marketing` | Tenant Marketing：活动、优惠券、同意、发放与核销资格 | 订单总金额、支付交易、会员余额 |
| `saas-admin` | SaaS Admin BFF：平台运营后台 + 租户后台适配、聚合查询 | **上述任一领域权威写表** |

另两条关键规定：
- **L30**：`Tenant Access` 是**一个**限界上下文，内部以 `tenant`、`authorization` **子域**组织代码和表；**IAM 的角色/权限/范围/授权变更由 Tenant Access 权威维护**；其他领域**禁止直接访问 `iam_` 表**。
- **L32**：**User 上下文退回 IM 域**（IM 账号认证与失效）；`user.role`/`ROLE_*` 仅服务 IM 后台，**不属于 SaaS 授权**。**IM 后台（`im-admin-service`）与 SaaS 后台（`platform-admin-service`）独立**，IM 后台**不在**该 SaaS 限界上下文表内。

---

## 三、当前现状（实测：服务 → 迁移根 → 表前缀分布）

| 服务（可部署） | 库 | 迁移根 | 实际使用前缀（表数） |
| --- | --- | --- | --- |
| `im-user-service` | `im_server` | `migration` | **`user` (20)**、**`open` (3)** ⚠️ |
| `im-message-service` | `im_server` | `migration` | `msg` (15) |
| `im-conversation-service` | `im_server` | `migration` | `conversation` (8)、**`user` (1)** ⚠️ |
| `im-admin-service` | `im_server` | `migration` | `adm` (16)、`adm_app_release*` ⚠️ 已退役 |
| `common-media-service` | `im_server` | `migration` | `media` (5) |
| `platform-identity-service` | `open_im` | `migration` | `idt` (4) |
| `platform-tenant-service` | `open_im` | `migration` | **`iam` (6)**、`tnt` (9) |
| `platform-admin-service` | `open_im` | `migration` | **`iam` (1)**、**`saa` (1)** ⚠️ |
| `platform-resource-service` | `open_im` | `migration` | `res` (5) |
| `platform-order-service` | `open_im` | `migration` | `ord` (17) |
| `platform-customer-service` | `open_im` | `migration` | `cst` (9) |
| `platform-marketing-service` | `open_im` | `migration` | `mkt` (7) |
| `common-payment-service` | `open_im` | `migration` | `pay` (11)、**`tnt` (1)** ⚠️ |
| `common-payment-channel-service` | `open_im` | `migration` | **`pay` (2)** ⚠️（登记的是 `paych_`） |
| `common-audit-service` | 双根 | `migration` / `migration-audit` | **`iam` (2 / 3)** ⚠️（登记的是 `audit_`） |
| `common-mail-service` | `open_im` | `migration` | `mail` (3) |
| `common-sms-service` | `open_im` | `migration` | `sms` (3) |
| `group-idaas-service` | `open_im` | `migration` | **`group` (2)**、**`idaas` (1)** ⚠️ |
| `gateways/im-access-ws` | — | 无迁移根 | **0 张表** |

**登记清单现状**（`table-prefixes.txt`，18 个）：
`user` `msg` `conversation` `adm` `ord` `idt` `tnt` `iam` `res` `pay` `cst` `mkt` `media` `audit` `mail` `sms` `paych` `ws`

**未登记却被使用**（4 个 / 7 张表，即 `flyway-conventions` 剩余 7 条）：
`open` (3) · `saa` (1) · `group` (2) · `idaas` (1)

**登记了但 0 张表用**（3 个）：`audit`（实际用 `iam_audit_*`）· `paych`（实际用 `pay_channel_*`）· `ws`（无迁移根）

---

## 四、现状偏差清单（这是规范要修的东西）

### 偏差 1：`iam_` 被两个限界上下文共用 ⚠️ 最严重

- `platform-tenant-service` 建 `iam_role`、`iam_permission`、`iam_user_role`、`iam_role_permission`、`iam_approval`、`iam_consumer_application`（**6 张**）。
- `platform-admin-service` 建 `iam_menu`（**1 张**）。
- 而设计文档 L28 说 `saas-admin` **"不拥有上述任一领域权威写表"**，L30 说 IAM 权威归 **Tenant Access**。
- 即：**`iam_menu` 建在了一个"不拥有权威写表"的服务里**，而它与 `iam_role` 共用同一个前缀——**违反 `:156` 的"全局唯一（语义）"**。门禁因 L63-65 只查"出现"，查不出来。

### 偏差 2：`open` 命名取自"开源"，无区分度

- 3 张表：`open_application`、`open_application_scope`、`open_user_authorization`。
- 代码证据：它们属**完整的 OAuth 2.0 授权服务器**——`OauthConsentController`、`RequestMapping("/oauth/consent")`、scope 勾选、签发授权码、`open_user_authorization` 即用户对应用的授权。
- 若 `open` 意为"开源"（本项目整体开源），则**对所有表都成立、零区分度**，不能作为前缀语义（同理不可有 `free_`/`oss_`）。

### 偏差 3：`group` 与 `idaas` 是同一上下文的两个名

- `group-idaas-service` 同时使用 `group_account`、`group_org`、`idaas_client`。
- 代码证据：三者同在 `io.openware.group.idaas` 包；该服务只有 `AuthController`（`/login`、`/session`、`/logout`、`/sso-ticket`）与 `ClientController`。
- **一个可部署服务占用两个前缀**，违反"一个前缀 ↔ 一个上下文"。

### 偏差 4：`saa` 是 `saas` 的截断

- `saa_admin_account`（1 张）。设计文档称该上下文为 **`saas-admin`**。

### 偏差 5：跨域建表（服务建了别的域的表）

| 表 | 建在 | 语义归属 | 说明 |
| --- | --- | --- | --- |
| `tnt_payment_method` | `common-payment-service` | 支付域（登记时已有结论：payment 拥有它） | 已在批次 2 核实并留档 |
| `user_conversation_mute` | `im-conversation-service` | 会话域（用户侧设置） | 归属待你判 |
| `iam_menu` | `platform-admin-service` | Tenant Access（按 L30）或后台本地读模型 | 见偏差 1 |
| `adm_app_release`、`adm_app_release_retired_v1` | `im-admin-service` | **已退役**（被客户端发布治理取代） | 基线内以注释留档 |

### 偏差 6：登记了但无表

- `audit`：0 张表用，审计表实际是 `iam_audit_log` / `iam_audit_idempotency` / `iam_audit_archive_manifest`。
- `paych`：0 张表用，支付渠道表实际是 `pay_channel_provider` / `pay_channel_transaction`。
- `ws`：登记为"长连接接入"，**无迁移根、0 张表**。

---

## 五、业界规范要点（供你判断用）

1. **表前缀 = 限界上下文的命名空间分配**。业界（DDD + 多模块单库）通行做法是"一个上下文一份前缀"，页面上常以"命名空间/前缀表"登记，与这里 `:158` 的登记表思路一致。
2. **前缀取自领域词汇（Ubiquitous Language），不取自项目属性**。`open`（开源）、`free`、`oss`、`core`、`common` 这类"属性词"无区分度，业界不用于前缀。区分度的判据是：**换掉它，命名空间是否还能唯一确定一个上下文**。
3. **缩写要稳定且全局唯一**（`:156` 已写明）。同一缩写出现两次即失效——`iam_` 现状正是反例。
4. **服务与命名空间尽量一一对应**（一个服务 = 一个库/一份前缀），便于按服务授权、备份、迁移。当前 `im-conversation-service` 的 `user_` 表、`common-payment-service` 的 `tnt_` 表属于例外，业界通常**要么并入本域前缀、要么明确定义为跨域共享表**。
5. **命名冲突要防**：`group` 在 SQL 中是保留字（`GROUP BY`），作前缀虽可加下划线规避，但检索与阅读成本高；业界倾向用更具体的域词（此例即 `idaas_`）。

---

## 六、建议（待你决策）

### 建议 A：登记表增加一列"限界上下文 / 归属服务"（**根治点**，优先级最高）

理由：门禁只能查"登记了没"，查不出"归谁"，所以偏差 1 一直无人发现。补上这一列后，"一个前缀一个上下文"才第一次**可被评审**。

18 个已登记前缀的建议归属（依据见括号）：

| 前缀 | 限界上下文 / 归属 | 依据 |
| --- | --- | --- |
| `user` | IM 域 · `im-user-service` | L32 User 退回 IM 域 |
| `msg` | · `im-message-service` | 服务迁移根 |
| `conversation` | · `im-conversation-service` | 同上 |
| `adm` | **IM 后台** · `im-admin-service` | L32 IM 后台与 SaaS 后台独立、不在 SaaS 表内 |
| `media` | 通用支撑域 · `common-media-service` | L181 |
| `mail` | 通用支撑域 · `common-mail-service` | L181 |
| `sms` | 通用支撑域 · `common-sms-service` | L181 |
| `idt` | Platform Identity & Entry · `platform-identity-service` | L21 |
| `tnt` | Tenant Access · `platform-tenant-service` | L22 |
| `iam` | **Tenant Access 的授权子域**（权威在 `platform-tenant-service`） | L30 |
| `res` | Resource Scheduling | L23 |
| `ord` | Transaction Fulfillment | L24 |
| `pay` | Funds Collection | L25 |
| `cst` | Tenant Loyalty | L26 |
| `mkt` | Tenant Marketing | L27 |
| `audit` | 通用支撑域（**预留**，实际用 `iam_audit_*`） | L181 / :168 |
| `paych` | 通用支撑域·支付域子域（**预留**，实际用 `pay_channel_*`） | L181 / :171 |
| `ws` | 接入网关（**保留**，无表） | :172 |

### 建议 B：新增 3 行登记（体例沿用 `audit_`/`paych_` 的"登记 + 差异留档"）

> 依据 `:182`：**"既有表不得仅为统一缩写而重命名"**，且本仓对 `audit_`/`paych_` **正是照原样留档**。故下面**只登记、不改名**。

| 领域 | 领域职责 | 前缀 | 状态 | 示例/说明 |
| --- | --- | --- | --- | --- |
| **Open Platform**（开放授权 / Authorization Server） | 第三方应用注册、scope 定义、用户授权同意与授权码签发（OAuth 2.0） | `open_` | 二阶段 | `open_application`、`open_application_scope`、`open_user_authorization`。**语义注明**：`open` 取自**"开放平台"**，**非"开源"**——本项目整体开源，"开源"无区分度，不作前缀语义 |
| **IDaaS**（集团认证服务） | 集团级身份即服务：集团账号、集团组织、SSO 票据、OAuth 客户端（`group-services/idaas/group-idaas-service`） | `idaas_` / `group_` | 二阶段 | `idaas_client`；集团账号与组织目录沿用 `group_account`、`group_org`（历史口径）。**注明**：`group_org` 是**集团 IdP 自有的组织目录**，与 SaaS 的 `tnt_organization` **不是同一事实**，**不并入 `tnt_`** |
| **SaaS Admin** | 平台运营后台 + 租户后台（BFF）：聚合查询与适配（`platform-services/admin/platform-admin-service`） | `saas_` | 二阶段 | 后台账号沿用 `saa_admin_account`（`saa` 为 `saas` 的历史截断）；`iam_menu` 系后台本地读模型，**权威 IAM 模型仍归 Tenant Access** |

### 建议 C：`iam_` 的归属在登记表里写明确（配合建议 A）

在 `iam` 行注明：**权威写方是 `platform-tenant-service`（Tenant Access）**；`platform-admin-service` 的 `iam_menu` 属**后台本地读模型**、为历史口径。这样"谁有权写 `iam_` 表"第一次被写清，也才与 L30 自洽。

### 建议 D（可选，非必需）：清理 3 个"登记了但无表"的前缀

`audit` / `paych` / `ws` 目前**不违规**（已登记且已文档化），保留可表达"预留"。若要清理，**必须先删文档行、再删清单行**。⚠️ 但注意：`audit` 与 `paych` 的"预留"正是当前解释 `iam_audit_*` / `pay_channel_*` 偏差的载体，**删掉会让那两处变成"未登记前缀"**——建议**保留**。

---

## 七、需要你决策的清单

| # | 决策项 | 选项 | 影响 |
| --- | --- | --- | --- |
| D1 | `open_` 的领域定名 | ① 登记为 **Open Platform（开放授权）** ② 视为新上下文另起名 ③ 并入其他域 | 决定建议 B 第 1 行 |
| D2 | `group_` 与 `idaas_` 是否收敛为一个 | ① 两行都登记（承认同一服务两名，§182 允许留档）② 只登记 `idaas_`、`group_` 作历史口径注明 | 决定建议 B 第 2 行形态 |
| D3 | `saa_` 处理 | ① 登记 `saas_` 并注明 `saa_` 为历史截断 ② 仅登记 `saa_` | 决定建议 B 第 3 行 |
| D4 | `iam_` 归属表述 | ① 按建议 C 明确"权威在 Tenant Access、`iam_menu` 为后台读模型" ② 另定 | 关系到 L30/L28 自洽 |
| D5 | 是否采纳建议 A（增加"限界上下文"列） | 是 / 否 | 决定能否长期防止偏差 1 复发 |
| D6 | 跨域表（`tnt_payment_method`、`user_conversation_mute`）是否在登记表显式定义为"跨域共享表" | 是 / 否 | 避免日后重复讨论 |

**决策后我的执行顺序**：① 改 `ENGINEERING_RULES.md` 登记表（含新增列与 3 行）→ ② 同步 `table-prefixes.txt` → ③ 跑 `validate-flyway-conventions.ps1` 确认 7 → 0 → ④ 该门禁转阻断。

> **不建议的做法**：把 `open`/`group`/`saa` 直接塞进 `table-prefixes.txt` 了事。门禁会绿，但等于把"按项目属性命名"和"一个服务两个前缀"**固化成规范**，之后更难改——而 `:156` 恰恰要求"语义稳定、全局唯一"。
