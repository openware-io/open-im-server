# 前缀登记表补充建议（待架构确认后并入 docs/ENGINEERING_RULES.md）

> 状态：**建议稿，未并入**。`ENGINEERING_RULES.md:182` 规定"新领域、新前缀或前缀语义变更必须先完成架构确认，并在上表及对应领域设计中登记后才能创建 Flyway 表；既有表**不得仅为统一缩写而重命名**"。因此本稿**只做登记与差异留档，不改名**。
>
> 采用与 `audit_` / `paych_` 两行**同一体例**（"预留/尚无表使用；现有表沿用 X（属历史口径）"），因为那正是本仓已有的、由规范认可的处置方式。

## 建议插入的三行

按表中现有的**全局字母序**位置插入：

| 位置 | 领域 | 领域职责 | 数据库表前缀 | 状态 | 示例/说明 |
| --- | --- | --- | --- | --- | --- |
| 在 `Identity` 行之后 | IDaaS（集团认证服务） | 集团级身份即服务：集团账号、集团组织、SSO 票据、OAuth 客户端（`group-services/idaas/group-idaas-service`） | `idaas_` / `group_` | 二阶段 | `idaas_client`；集团组织目录沿用 `group_account`、`group_org`（属历史口径：服务建成于 `idaas_` 登记之前，其语义为**集团 IdP 自有的账号/组织目录**，与 SaaS 的 `tnt_organization` 不是同一事实，不并入 `tnt_`） |
| 在 `Iam` 行之后 | SaaS Admin | 平台运营后台 + 租户后台（BFF）：聚合查询与适配（`platform-services/admin/platform-admin-service`） | `saas_` | 二阶段 | 后台账号沿用 `saa_admin_account`（`saa` 为 `saas` 的历史截断写法，属历史口径）；后台菜单 `iam_menu` 系后端本地读模型，**权威 IAM 授权模型仍归 IAM 域**（`iam_role`/`iam_permission`/`iam_user_role`/`iam_approval` 在 `platform-tenant-service`） |
| 在 `Media` 行之后 | Open Platform（开放授权 / Authorization Server） | 第三方应用注册、scope 定义、用户授权同意与授权码签发（OAuth 2.0） | `open_` | 二阶段 | `open_application`、`open_application_scope`、`open_user_authorization`。**注**：前缀名 `open` 取自"开放平台"，**不是"开源"**（本项目整体开源，故"开源"在本仓无区分度，不可作为前缀语义）；该上下文在业界通称 Authorization Server / 开放授权 |

## 为什么不建议改名（尽管领域上更整齐）

三条**规范内**的理由，与上面的 A/B 讨论一致但结论相反：

1. **§182 明文禁止**：`saa_` → `saas_` 属"仅为统一缩写"，被直接禁止；`open_`/`group_` 的改名同样会牵动既有表。
2. **本仓已有先例**：`audit_`（登记了但 0 表使用，实际用 `iam_audit_*`）与 `paych_`（实际用 `pay_channel_*`）**都是照原样留档、没有改名**。沿用同体例可保持规范自洽。
3. **排序约束**：改名必须在**冻结 tag 之前**完成，否则只能新增 V2 做 RENAME；而冻结 tag 还依赖批次 5 全绿。现在改名会把批次 2 拖进一个跨批次的改动。

## 若要改名（备选，需你明确授权）

领域上确实更整齐（一个前缀 = 一个限界上下文）。代价实测：

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

## 落地顺序（两条路都一样）

① 架构确认 → ② 改 `docs/ENGINEERING_RULES.md` 登记表（**根治点**：写明每个前缀属于哪个限界上下文）→ ③ 同步 `scripts/validate/table-prefixes.txt` → ④ 若走改名路线，再改名并跑门禁 → ⑤ `flyway-conventions` 自然归零并可转阻断。

> ⚠️ 注意两处门禁耦合（实测）：`validate-flyway-conventions.ps1` **L167** 要求建表前缀已登记；**L63-65** 要求每个已登记前缀都必须出现在 `ENGINEERING_RULES.md` 的前缀表里。所以"登记"必须**两处同改**；若日后要移除某个预留前缀，必须**先删文档行、再删清单行**，否则立刻报 `registered prefix ... is not documented`。
