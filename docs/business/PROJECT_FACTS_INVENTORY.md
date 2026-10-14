# 项目事实清单（Project Facts Inventory）

> **性质**：本文登记**工程的既有事实**——业务线、域、服务归属、表命名空间及其偏差。
> **维护约定**：**新增域、新增服务、新增/变更表前缀、发现或修掉偏差时，必须同步更新本文**。本文是"当前工程实际长什么样"的唯一权威来源；规范回答"应当如何"，改造方案回答"如何从现状到规范"。
> **最后更新**：批次 2 迁移基线重建期间（2026-10-13）。所有数据均为实测（各服务 `src/main/resources/db/migration*/` 与 PO `@TableName`）。

---

## 1. 工程分层（三层）

```text
开源工程（整仓开源：open-im-server）
├── IM 业务线（im-services/ + gateways/）—— 类比微信，是「开放平台」本身
├── SaaS 业务线（platform-services/ + common-services/）—— 建立在 IM 之上的垂直业务
└── 集团层（group-services/）—— 统管 IM 与 SaaS，只管理、不参与业务
```

| 层 | 定位 | 代码根 | 数据库 | 权威依据 |
| --- | --- | --- | --- | --- |
| **IM 业务线** | 类微信的**开放平台**：提供入口、身份授权、用户触达 | [`im-services/`](../../im-services/)、[`gateways/`](../../gateways/) | `im_server` | [IM_OPEN_PLATFORM_01_SERVICE](../renovation/IM_OPEN_PLATFORM_01_SERVICE.md) |
| **SaaS 业务线** | 建立在 IM 之上的垂直业务（当前是开放平台**第一个接入方**） | [`platform-services/`](../../platform-services/)、[`common-services/`](../../common-services/) | `open_im`（规范称 `gv_saas`） | [SAAS_PLATFORM_09_SERVICE](../renovation/SAAS_PLATFORM_09_SERVICE.md) |
| **集团层** | 集团统一账号/组织/登录 SSO，**只管理、不参与业务** | [`group-services/`](../../group-services/) | 随调用方 | [ACCOUNT_PERMISSION_MODEL](../business/ACCOUNT_PERMISSION_MODEL.md) |

**业务线之间的关键关系**（务必理解，命名冲突多源于此）：

1. **IM 是开放平台，SaaS 是它的第一个第三方接入方**（"美团之于微信"）。打车/电商/本地生活未来按**同一标准流程**接入。见 [IM_OPEN_PLATFORM_01_SERVICE](../renovation/IM_OPEN_PLATFORM_01_SERVICE.md)。
2. **IM 与 SaaS 互相引用，但各有权威**：
   - SaaS 账号通过 **`idt_oauth_link`**（`provider='IM'`）绑定 IM 账号，schema 注释为"第三方提供方 IM"；
   - SaaS 平台账号 `idt_account`、SaaS 后台账号 `saa_admin_account`（注释"SaaS后台管理员账号"，含 `im_account` 绑定 IM 运营账号、`platform_account_id` 关联 `idt_account`）。
3. **集团 → SaaS 的唯一关联是 SSO 映射**：`saa_admin_account.idaas_subject` → `group_account.id`。**业务表不引用集团 IDaaS 表。**
4. ⚠️ **`idt_*` 是 SaaS 平台账号，不是 IDaaS**。IDaaS 是集团的 `group_account`。[ACCOUNT_PERMISSION_MODEL](../business/ACCOUNT_PERMISSION_MODEL.md) §3 专门就此警告。

---

## 2. 域 → 服务 → 命名空间（当前事实）

| 业务线 | 域（限界上下文） | 表前缀 | 权威服务 | 表数 | 备注 |
| --- | --- | --- | --- | --- | --- |
| IM | User（IM 账号/社交/积分） | `user` | `im-user-service` | 20 | |
| IM | **开放平台**（第三方应用注册/scope/用户授权） | `open` | `im-user-service` | 3 | 包名 `openplatform`；OAuth 2.0 授权服务器 |
| IM | Message | `msg` | `im-message-service` | 15 | |
| IM | Conversation | `conversation` | `im-conversation-service` | 8 | |
| IM | IM 后台 | `adm` | `im-admin-service` | 16 | 与 SaaS 后台**独立** |
| IM | 长连接接入 | `ws` | `im-access-ws` | 0 | **预留**，无迁移根 |
| SaaS | Identity（平台账号/登录） | `idt` | `platform-identity-service` | 4 | |
| SaaS | **SaaS 后台** | ⚠️ `saa` **未登记** | `platform-admin-service` | 1 | `saa_admin_account`（SaaS 后台管理员账号） |
| SaaS | Tenant Access（租户/组织/门店/配置） | `tnt` | `platform-tenant-service` | 9 | |
| SaaS | **经营 IAM**（角色/权限/作用域授权） | `iam` | `platform-tenant-service` | 6 | 授权权威在此 |
| SaaS | ⚠️ 后台菜单 | ⚠️ 误用 `iam_` | `platform-admin-service` | 1 | `iam_menu`，实为**菜单/导航模型** |
| SaaS | Resource Scheduling | `res` | `platform-resource-service` | 5 | |
| SaaS | Transaction Fulfillment | `ord` | `platform-order-service` | 17 | 业态 KTV/HOTEL/SPA/RETAIL 是**列** |
| SaaS | Funds Collection | `pay` | `common-payment-service` | 11 | |
| SaaS | Tenant Loyalty | `cst` | `platform-customer-service` | 9 | |
| SaaS | Tenant Marketing | `mkt` | `platform-marketing-service` | 7 | |
| 通用支撑 | Media | `media` | `common-media-service` | 5 | |
| 通用支撑 | Mail | `mail` | `common-mail-service` | 3 | |
| 通用支撑 | SMS | `sms` | `common-sms-service` | 3 | |
| 通用支撑 | Audit | `audit` | `common-audit-service` | 0 | **预留**，实际用 `iam_audit_*` |
| 通用支撑 | Payment Channel | `paych` | `common-payment-channel-service` | 0 | **预留**，实际用 `pay_channel_*` |
| 集团 | 集团认证服务（IDaaS） | `idaas` **未登记** / `group` **未登记** | `group-idaas-service` | 1 / 2 | 同属 `io.openware.group.idaas` 域 |

**已登记前缀**（18 个，见 [`scripts/validate/table-prefixes.txt`](../../scripts/validate/table-prefixes.txt)）：
`user` `msg` `conversation` `adm` `ord` `idt` `tnt` `iam` `res` `pay` `cst` `mkt` `media` `audit` `mail` `sms` `paych` `ws`

---

## 3. 偏差清单（待处理）

| # | 偏差 | 事实 | 影响 |
| --- | --- | --- | --- |
| **V1** | `iam_` 被两个域共用 | `platform-tenant-service` 建 6 张（`iam_role`/`iam_permission`/`iam_user_role`/`iam_role_permission`/`iam_approval`/`iam_consumer_application`）；`platform-admin-service` 建 `iam_menu`（菜单/导航模型） | 违反"一前缀一域"；门禁查不出 |
| **V2** | `open` 未登记 | 3 张表：`open_application`、`open_application_scope`、`open_user_authorization` | 门禁报"未登记前缀" |
| **V3** | `saa` 未登记 | 1 张：`saa_admin_account` | 同上 |
| **V4** | `group` 未登记 | 2 张：`group_account`、`group_org` | 同上 |
| **V5** | `idaas` 未登记 | 1 张：`idaas_client` | 同上 |
| **V6** | ⚠️ **`saa_` 与 `group_` 同音同形** | `saa`=SaaS 后台、`group`=集团；`saa_admin_account` **与集团无关**（注释为"SaaS后台管理员账号"） | 全局命名空间易混淆，是本清单最需要消歧的一处 |
| **V7** | 跨域建表 | `tnt_payment_method` 建在 `common-payment-service`；`user_conversation_mute` 建在 `im-conversation-service`；`iam_menu` 建在 `platform-admin-service`；`adm_app_release*` 已退役 | 需显式标注权威写方 |
| **V8** | 3 个前缀登记了但 0 表 | `audit`/`paych`/`ws` | 需明确"预留"状态；**不要删**——它们是 `iam_audit_*`/`pay_channel_*` 的留档载体 |

**门禁现状**：`validate-flyway-conventions.ps1` 报 **7 条**（= V2+V3+V4+V5 共 7 张表）。另两个耦合点：**L167** 查前缀已登记；**L63-65** 查已登记前缀必须出现在 [ENGINEERING_RULES.md](../ENGINEERING_RULES.md)（反引号正则）。→ 登记须**两处同改**；移除须**先删文档行再删清单行**。

---

## 4. 命名规范要点（与规范正文的关系）

- **通用规范**：[10 DDD 服务工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) 定义"**一个限界上下文、一个服务发布边界**"、数据所有权与迁移归属，**但不含本仓前缀清单**（该规范第 305 行明确不记录具体项目的历史包路径与存量违规）。
- **项目工程规则**：[ENGINEERING_RULES.md](../ENGINEERING_RULES.md) 的"领域与数据库表前缀登记表"是**本仓前缀的登记处**，也是门禁校验对象。
- **原则**：一个前缀 = 一个域 = 一个权威服务；前缀取自**领域词汇**，不取自业务线名或组织层级；业态/租户/状态等**判别维度用列**，不建域。

> 变更规范或前缀前，先更新本文的对应行，再改规范与清单——**本文是事实来源**。

---

## 5. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-10-13 | 首次建立。记录三层层级、22 个域/命名空间、8 条偏差（V1–V8）、门禁现状 7 条。 |
