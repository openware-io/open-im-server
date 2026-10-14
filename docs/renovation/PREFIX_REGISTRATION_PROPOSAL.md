# 前缀登记表补充建议（待架构确认后并入 docs/ENGINEERING_RULES.md）

> 状态：**建议稿，未并入**。`ENGINEERING_RULES.md:182` 规定"新领域、新前缀或前缀语义变更必须先完成架构确认，并在上表及对应领域设计中登记后才能创建 Flyway 表；既有表**不得仅为统一缩写而重命名**"。因此本稿**只做登记与差异留档，不改名**。
>
> 采用与 `audit_` / `paych_` 两行**同一体例**（"预留/尚无表使用；现有表沿用 X（属历史口径）"），因为那正是本仓已有的、由规范认可的处置方式。

---

## 一、登记表缺一列：**该前缀属于哪个服务/限界上下文**

现表 6 列（领域 / 领域职责 / 表前缀 / 状态 / 示例）里，**"领域"是**概念名，"领域职责"是散文**，没有任何一列把前缀绑到**可部署服务或限界上下文**。所以下面这类不一致**现在无法从表里看出来**、也没有门禁能查：

- `iam_` 被**两个上下文**共用：`platform-tenant-service` 建 `iam_role`/`iam_permission`/`iam_user_role`/`iam_approval`（设计文档 `SAAS_PLATFORM_09_SERVICE.md:30` 明确 **Tenant Access 权威维护 IAM**），而 `platform-admin-service` 建 `iam_menu`（该服务在设计文档 L28 是 **`saas-admin` BFF，不拥有任何领域权威写表**）。
- `ws_` 登记为"长连接接入"，但**没有任何服务建 `ws_` 表**。

建议**增加一列"服务/限界上下文"**，并按下表把 18 个前缀一一绑定。这正是规范 L182 要求的"登记"，也是让 `flyway-conventions` 能长期归零的根因修复（现在门禁只能查"前缀有没有登记"，**查不出"这个前缀归谁"**）。

### 18 个已登记前缀的限界上下文归属（依据：设计文档 + 各服务迁移基线 + `@TableName`）

| 前缀 | 领域 | 限界上下文 / 归属服务 | 依据 |
| --- | --- | --- | --- |
| `user` | User | IM 域（`im-services/user/im-user-service`） | `SAAS_PLATFORM_09_SERVICE.md:32`：User 上下文退回 IM 域 |
| `msg` | Message | `im-services/message/im-message-service` | 服务迁移基线 |
| `conversation` | Conversation | `im-services/conversation/im-conversation-service` | 服务迁移基线 |
| `adm` | Admin | **IM 后台**（`im-services/admin/im-admin-service`） | L32：IM 后台与 SaaS 后台**独立**、不在 SaaS 限界上下文表内 |
| `ord` | Order | Transaction Fulfillment | L24 |
| `media` | Media | 通用支撑域 | L181 |
| `audit` | Audit | 通用支撑域（`common-services/audit`）——**预留，0 表** | L168；实际用 `iam_audit_*` |
| `mail` | Mail | 通用支撑域 | L181 |
| `sms` | SMS | 通用支撑域 | L181 |
| `paych` | Payment Channel | 通用支撑域·支付域子域——**预留，0 表** | L171；实际用 `pay_channel_*` |
| `ws` | WebSocket | 接入网关（`gateways/im-access-ws`）——**保留，0 表** | L172 |
| `idt` | Identity | Platform Identity & Entry | L21 |
| `tnt` | Tenant | Tenant Access（`platform-tenant-service`） | L22 |
| `iam` | IAM | **Tenant Access 的授权模型**（`platform-tenant-service`）——⚠️ 见下 | L30 |
| `res` | Resource | Resource Scheduling | L23 |
| `pay` | Payment | Funds Collection | L25 |
| `cst` | Customer | Tenant Loyalty | L26 |
| `mkt` | Marketing | Tenant Marketing | L27 |

> ⚠️ **`iam` 的特殊性必须写明**：设计文档 L30 把 IAM 角色/权限/范围/授权的**权威**给了 Tenant Access；L28 又说 `saas-admin` 是 BFF、"不拥有上述任一领域权威写表"。但实际 `iam_menu` 建在 `platform-admin-service`。建议在登记表该行注明：**`iam_` 的权威归属是 Tenant Access；`platform-admin-service` 内的 `iam_menu` 是后台本地读模型，属历史口径**。这样"谁有权写 `iam_` 表"这件事第一次被写清楚。

---

## 二、建议新增的三行（待架构确认）

按表中现有的**全局字母序**位置插入，体例与 `audit_`/`paych_` 一致：

| 位置 | 领域 | 领域职责 | 数据库表前缀 | 状态 | 示例/说明 |
| --- | --- | --- | --- | --- | --- |
| `Identity` 行之后 | IDaaS（集团认证服务） | 集团级身份即服务：集团账号、集团组织、SSO 票据、OAuth 客户端（`group-services/idaas/group-idaas-service`） | `idaas_` / `group_` | 二阶段 | `idaas_client`；集团组织目录沿用 `group_account`、`group_org`（历史口径：服务建成早于 `idaas_` 登记；其语义为**集团 IdP 自有的账号/组织目录**，与 SaaS 的 `tnt_organization` 不是同一事实，**不并入 `tnt_`**） |
| `Iam` 行之后 | SaaS Admin | 平台运营后台 + 租户后台（BFF）：聚合查询与适配（`platform-services/admin/platform-admin-service`） | `saas_` | 二阶段 | 后台账号沿用 `saa_admin_account`（`saa` 为 `saas` 的历史截断写法）；后台菜单 `iam_menu` 系后端本地读模型，**权威 IAM 授权模型仍归 IAM 域 / Tenant Access** |
| `Media` 行之后 | Open Platform（开放授权 / Authorization Server） | 第三方应用注册、scope 定义、用户授权同意与授权码签发（OAuth 2.0） | `open_` | 二阶段 | `open_application`、`open_application_scope`、`open_user_authorization`。**注**：前缀名 `open` 取自**"开放平台"**，**不是"开源"**——本项目整体开源，故"开源"在本仓**无区分度**，不可作为前缀语义；该上下文在业界通称 Authorization Server / 开放授权 |

---

## 三、为什么不建议改名（尽管领域上更整齐）

三条**规范内**的理由：

1. **§182 明文禁止**：`saa_` → `saas_` 属"仅为统一缩写"，被直接禁止；`open_`/`group_` 的改名同样会牵动既有表。
2. **本仓已有先例**：`audit_`（登记了但 0 表使用，实际用 `iam_audit_*`）与 `paych_`（实际用 `pay_channel_*`）**都是照原样留档、没有改名**。沿用同体例可保持规范自洽。
3. **排序约束**：改名必须在**冻结 tag 之前**完成，否则只能新增 V2 做 RENAME；而冻结 tag 还依赖批次 5 全绿。现在改名会把批次 2 拖进跨批次改动。

## 四、若要改名（备选，需你明确授权，且需先放宽 §182）

| 现名 | 建议名 | Java 引用 | 说明 |
| --- | --- | --- | --- |
| `saa_admin_account` | `saas_admin_account` | 14 | 纯拼写修正——**被 §182 禁止**，需先改规范 |
| `open_application` | `oauth_application` | 5 | 语义更准（OAuth 授权服务器） |
| `open_application_scope` | `oauth_application_scope` | — | |
| `open_user_authorization` | `oauth_user_authorization` | 2 | |
| `group_account` | `idaas_account` | 1 | 收敛为单一 `idaas_` |
| `group_org` | `idaas_org` | 1 | |
| `idaas_client` | — 不变 | 1 | |
| `iam_audit_*`（3 张） | `audit_*` | — | 正好用上已登记的 `audit_` |

共 7 张表、约 23 处引用，需同步改**迁移文件 + PO `@TableName` + SQL + 索引名**，并更新本节登记表与 `scripts/validate/table-prefixes.txt`。

---

## 五、落地顺序（两条路都一样）

① 架构确认 → ② 改 `docs/ENGINEERING_RULES.md` 登记表（**根治点**：补"限界上下文"列 + 登记新前缀）→ ③ 同步 `scripts/validate/table-prefixes.txt` → ④ 若走改名路线，再改名并跑门禁 → ⑤ `flyway-conventions` 自然归零并可转阻断。

> ⚠️ 两处门禁耦合（实测）：`validate-flyway-conventions.ps1` **L167** 要求建表前缀已登记；**L63-65** 要求每个已登记前缀都必须在 `ENGINEERING_RULES.md` 里以反引号形式出现（正则 `` `prefix_?` ``，**只要出现即可，不限位置**——所以**增列不会破坏该检查**）。若日后要移除某个预留前缀，必须**先删文档行、再删清单行**，否则立刻报 `registered prefix ... is not documented`。
