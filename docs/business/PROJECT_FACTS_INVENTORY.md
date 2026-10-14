# 项目事实清单（Project Facts Inventory）

> **性质**：本文登记**工程的既有事实**——业务线、域、服务归属、表命名空间及其偏差。
> **维护约定**：**新增域、新增服务、新增/变更表前缀、发现或修掉偏差时，必须同步更新本文**。本文是"当前工程实际长什么样"的唯一权威来源；规范回答"应当如何"，改造方案回答"如何从现状到规范"。
> **最后更新**：批次 2 迁移基线重建期间（2026-10-13）。所有数据均为实测（各服务 `src/main/resources/db/migration*/` 与 PO `@TableName`）。
>
> 📐 **图集**：[工程架构图集](./ARCHITECTURE_DIAGRAMS.md) —— §一 产品业务图、§二 **领域架构图（域 → 表前缀 → 服务 → 库）**、§三 工程结构图、§四 技术栈架构图。本文是**逐行权威**，图集是**结构概览**。

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
| **V1** | `iam_` 跨两个服务 | 授权模型 5 张（`iam_role`/`iam_permission`/`iam_user_role`/`iam_role_permission`/`iam_approval`）+ `iam_consumer_application` 在 `platform-tenant-service`；**`iam_menu`** 在 `platform-admin-service` | **有历史原因，非单纯缺陷**——见 §3.1 |
| **V2** | `open` 未登记 | 3 张表：`open_application`、`open_application_scope`、`open_user_authorization` | 门禁报"未登记前缀" |
| **V3** | `saa` 未登记 | 1 张：`saa_admin_account` | 同上 |
| **V4** | `group` 未登记 | 2 张：`group_account`、`group_org` | 同上 |
| **V5** | `idaas` 未登记 | 1 张：`idaas_client` | 同上 |
| **V6** | ⚠️ **`saa_` 与 `group_` 同音同形** | `saa`=SaaS 后台、`group`=集团；`saa_admin_account` **与集团无关**（注释为"SaaS后台管理员账号"） | 全局命名空间易混淆，是本清单最需要消歧的一处 |
| **V7** | 跨域建表 | `tnt_payment_method` 建在 `common-payment-service`；`user_conversation_mute` 建在 `im-conversation-service`；`iam_menu` 建在 `platform-admin-service`；`adm_app_release*` 已退役 | 需显式标注权威写方 |
| **V8** | 3 个前缀登记了但 0 表 | `audit`/`paych`/`ws` | 需明确"预留"状态；⚠️ 其中 **`audit` 空着**正是 V1 的技术债之一（见 §3.2） |

### 3.2 ⚠️ `iam_` 的两条技术债（实测确认）

**债 A：`iam_` 名不符实——"IAM" 里没有 Identity Management。**

`iam_` 下**没有任何身份/凭据表**。身份的权威在别处：`idt_*`（SaaS 平台账号，`idt_account`/`idt_login_identity`/`idt_oauth_link`）、`group_account`（集团账号）、`user_*`（IM 账号，含 `user` 主表）。而 `iam_` 的 6 张表全是**授权**语义：

| 表 | 关键列 | 性质 |
| --- | --- | --- |
| `iam_permission` | `code`/`module`/`resource`/`action`/`scope_level`/`grantable_levels`/`menu_code` | 权限定义 |
| `iam_role` | `code`/`role_type`/`scope_level`/`copy_from_role_id` | 角色 |
| `iam_role_permission` | 角色↔权限 | 权限绑定 |
| `iam_user_role` | `account_id`/`role_id`/`scope_type`/`effective_from`/`to`/`authorization_version` | 授权分配（`account_id` 仅**引用**账号） |
| `iam_approval` | `action_type`/`approver_id`/`review_comment` | 高风险动作复核 |
| `iam_consumer_application` | 消费端应用 | 应用注册 |

→ **判据**：DDL 里找不到 identity、credential、password、subject 任何一类列。所以此前缀实际表达的是**访问控制（授权）**，不是 Identity Management。

**债 B：`iam_audit_*` 属审计域却挂 `iam_` —— 真正的"一前缀多域"。**

| 归属服务 | `iam_` 表 | 真实域 |
| --- | --- | --- |
| `platform-tenant-service` | `iam_permission`/`iam_role`/`iam_role_permission`/`iam_user_role`/`iam_approval`/`iam_consumer_application` | 访问控制 |
| `common-audit-service` | `iam_audit_log`/`iam_audit_idempotency`/`iam_audit_archive_manifest` | **审计** |
| `platform-admin-service` | `iam_menu` | 控制台呈现 |

而**正确的 `audit` 前缀已登记、却 0 张表使用**——空着的正确名字与被占用的错误名字同时存在，是本清单里最该修的一处。

**整改分级**（债 B 优先，因其目标前缀**已登记**，不需新增前缀、不需架构确认）：

| 级别 | 动作 | 说明 |
| --- | --- | --- |
| **① 建议先做** | `iam_audit_*` → `audit_*`（`audit_log`/`audit_idempotency`/`audit_archive_manifest`） | 用上已登记的 `audit` 域；**零新增前缀**；消除"一前缀多域" |
| ② 待裁决 | `iam_` 是否更名以名实相符（如 `authz_`） | 涉及新增前缀 → 依 [ENGINEERING_RULES.md](../ENGINEERING_RULES.md) §182 须先架构确认 |
| ③ 不动 | `iam_menu` 保留 | §3.1 已证为 IAM 迭代的有意结果 |

### 3.3 架构分裂：认证在集团层、授权在 SaaS 层（实测）

这是 §3.2 "`iam` 名不符实"的**根因**，也是本工程最需要澄清的架构事实。

| 维度 | 集团层 `group-services/idaas` | SaaS 层 `platform-tenant-service` |
| --- | --- | --- |
| 职责 | **认证**：集团账号、组织、SSO、会话、JWT 签发 | **授权**：RBAC 角色/权限/作用域/审批 |
| 代码证据 | 全部 19 个类无授权概念；`Role`=**0**、`Permission`=**0**、`Grant`=**0**、`Policy`=**0** | `Role`=**260** 处、`Permission`=**245** 处、`Authoriz`=57 处 |
| 代表类/表 | `AuthController`、`AuthApplicationService`、`GroupJwtSigner`、`GroupSessionTtl`、`IdaasSessionStore`、`group_account`、`group_org`、`idaas_client` | `iam_role`、`iam_permission`、`iam_user_role`、`iam_approval` |

**即**：集团层**有身份、无授权**；SaaS 层**有授权、无身份**（身份的权威在 `idt_*`/`group_account`/`user_*`）。二者都属"身份与权限"范畴，却分属两层、且各自缺一半。

**这正是"`iam` 名不符实"的根因**：`iam`（Identity **and** Access Management）这个词**同时暗示身份与授权**，而工程把两者拆到了不同层——集团做身份（却叫 idaas），SaaS 做授权（却叫 iam）。**两个名字都只覆盖了自己真正职责的一半。**

> **待澄清的架构问题（非缺陷，需口径）**：集团层作为"统管两条业务线"的层级，是否**应当**拥有平台级授权模型？当前设计是"集团只管理、不参与业务"，授权完全在 SaaS。这是**有意边界**还是**待演进**，需产品/架构定调——它决定 `iam` 是改名（归位到 SaaS 的授权语义）还是**未来会被集团层收编**。

**门禁现状**：`validate-flyway-conventions.ps1` 报 **7 条**（= V2+V3+V4+V5 共 7 张表）。另两个耦合点：**L167** 查前缀已登记；**L63-65** 查已登记前缀必须出现在 [ENGINEERING_RULES.md](../ENGINEERING_RULES.md)（反引号正则）。→ 登记须**两处同改**；移除须**先删文档行再删清单行**。

### 3.1 `iam_` 跨服务的历史原因（实测 git 证据）

`iam` 是**按迭代逐步定义**的域：**初衷是"身份即服务"**，但按业务迭代逐步实现，**数据随消费方就近放置**，因此跨了两个服务。证据：

| 时间 | 提交 | 事实 |
| --- | --- | --- |
| 2026-09-21 | `4971e22` 初始开源提交 | `iam_role`/`iam_permission`/`iam_user_role`/`iam_role_permission` **已在 `platform-tenant-service`**（`V1__tnt_iam_baseline.sql`）；`iam_approval` 另立 `V5__tnt_iam_approval.sql`。**授权模型从第一天就在 tenant 侧。** |
| 2026-09-30 | `998f4f8` `feat(auth): implement tenant menu permissions and headquarters operations` | **`V7__create_iam_menu.sql`（在 `platform-admin-service`）与 tenant 的 `V28__iam_scope_metadata.sql` 同一次提交落地**——"菜单 + 总部运营"是 **IAM 迭代的下一步**，菜单按**使用它的控制台所在模块**放置。 |

**结论**：`iam_menu` 放在 `platform-admin-service` **是"消费方就近"的有意结果**，不是把表放错了域。因此：

- `iam` 是**一个域**，其数据按类别分属两个服务：**授权事实**（角色/权限/绑定/审批）在 `platform-tenant-service`；**控制台呈现**（菜单/导航）在 `platform-admin-service`。
- 由此**命名原则第 1 条需要细化**：不是"一个域只能由一个服务建表"，而是"**一个域 = 一个前缀**；域内每类数据有唯一权威服务"（见 [ENGINEERING_RULES.md](../ENGINEERING_RULES.md) 命名原则第 1 条）。
- ⚠️ **但"就近放置"只解释了 `iam_menu`，解释不了下面两条——它们是技术债**（见 §3.2）。

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
| 2026-10-13 | §3.1：查证 git 历史，`iam_` 跨服务源于 IAM 按迭代逐步定义、数据随消费方就近放置（撤销原 V1 判定）。§3.2：新增两条实测技术债——`iam_` 名不符实（无 Identity Management）、`iam_audit_*` 属审计域却占 `iam_`。 |
