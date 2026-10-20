> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/renovation/PREFIX_CHANGE_SAAS_ADMIN.md

# 前缀变更规范：`saa_` 与后台控制台域

> 供你裁决的执行预案。**未改动代码或登记表。**
>
> **裁决进展**：**G1 已定案——采用 `console_`**（用户 2026-10-14 确认）。**V1 已撤销**：查证 git 历史后确认 `iam_` 跨服务是"IAM 按迭代逐步定义、数据随消费方就近放置"的有意结果，`iam_menu` **不需要改名**（详见 [项目事实清单](../../domain/PROJECT_FACTS_INVENTORY.md) §3.1）。因此本文件**只剩 `saa_admin_account` 一项改名**。

---

## 1. 为什么 `saa` 必须改（按已定规范判定）

`ENGINEERING_RULES.md` 命名原则第 2 条：**前缀取自领域词汇，不得取自业务线名或其他非领域词**。

`saa` 同时踩两条：

| 问题 | 说明 |
| --- | --- |
| **是业务线名** | `saa` 是 **saa**s 的截断——业务线名进表名的典型。规范已明确"业务线不写进表名"，所以它不是"缩写不好"，而是**层级用错**。 |
| **是截断且非域词** | 业界缩写取**域名**（如 `tnt`←tenant、`ord`←order），不取业务线的残词。 |

> 注意与 `open` 的区别：`open` 是**开源**（项目属性）；`saa` 是**SaaS**（业务线名）。两者都不得进表名，但理由不同。

---

## 2. 那个"域"是真实存在的，叫什么才是关键

实测 `platform-admin-service`：

- 对外路由全部在 **`/admin`** 下（`/admin/auth`、`/admin/staff`、`/admin/ktv`、`/admin/media`、`/admin/reports`、`/admin/reservations`、`/admin/tenant/overview`…）；
- 设计文档 [SAAS_PLATFORM_09_SERVICE](SAAS_PLATFORM_09_SERVICE.md) L28 定义它为 **`saas-admin` = SaaS Admin BFF**，"不拥有上述任一领域权威写表"——**实测吻合**：它聚合各域，不建业务域的表。
- 但它**确实有自己的数据**，且不是业务事实：
  - `saa_admin_account`（COMMENT `'SaaS后台管理员账号'`）：控制台**登录账号**——`username`/`password_hash`/`role`(SUPER_ADMIN/PLATFORM_ADMIN/TENANT_ADMIN)/`status`/`im_account`/`idaas_subject`/`platform_account_id`。
  - `iam_menu`（COMMENT `'平台/租户/门店菜单定义'`）：控制台**菜单与导航**——`code`/`name`/`path`/`icon`/`i18n_key`/`sort_no`/`scope_level`(PLATFORM/TENANT/STORE)/`required_permission`。

**结论**：这不是"业务域"，而是**管理控制台自身的支撑数据**——一个**合法但非业务**的域。所以正确做法不是"塞回业务线"，而是**给这个域取一个域词**。

### 域名候选（这是需要你定的核心）

| 候选 | 依据 | 评价 |
| --- | --- | --- |
| **`console_`** | 业界通用；直指"控制台/后台" | ✅ **推荐**：是域词、2 个音节、无歧义；`console_admin_account` / `console_menu` 语义自明 |
| `admin_` | 业界也很常用 | ⚠️ 会让**同一业务线出现两个 Admin 语义**（IM 侧是 `adm_`），易混 |
| `bff_` / `ui_` | 描述技术分层 | ❌ 违反"前缀不得取自技术分层" |
| 保留 `saa_` | 改动最小 | ❌ 违反第 2 条，且与 `group`**同音同形**（V6） |

> 我先前不建议 `saas_`（那是业务线名），现在按规范给出 **`console_`**。

---

## 3. 变更规范（改名要按这个机械流程走，避免漏改）

**目标**：统一到 `console_` 域。同时修掉 **V1**（`iam_` 一前缀两域）。

| 现名 | 新名 | 理由 |
| --- | --- | --- |
| `saa_admin_account` | `console_admin_account` | 去业务线名 |
| `iam_menu` | `console_menu` | 从 `iam_` 域移出（它只是**引用**权限，不定义权限），修 V1 |

**必须同步修改的 5 类（漏一类就出运行时缺陷）**：

| # | 位置 | 具体 | 数量（实测） |
| --- | --- | --- | --- |
| 1 | **迁移 DDL** | `CREATE TABLE`、`COMMENT`、**索引名**（规范要求 `uk_/idx_<表名>_<语义>`，改表名必改索引名）、种子 `INSERT` | `saa_admin_account` 16 处 / `iam_menu` 6 处（`*.sql`） |
| 2 | **PO `@TableName`** | 注解里的字符串 | 各 1 处 |
| 3 | **代码内表名字符串** | 原生 SQL、注释引用 | `saa_admin_account` 14 处 / `iam_menu` 1 处（`*.java`） |
| 4 | **登记表** | `docs/ENGINEERING_RULES.md` 前缀表**新增 `console` 行** + `iam` 行注明"菜单已移出" | 2 处 |
| 5 | **前缀清单** | `scripts/validate/table-prefixes.txt` 加 `console` | 1 处 |

**PO 类名是否跟着改？** `SaaAdminAccountPo`（被引 56 处）、`AdminMenuPo`（26 处）——**技术上是可选的**（只改注解字符串即可）。但**建议一并改名**（`ConsoleAdminAccountPo`/`ConsoleMenuPo`），否则留下一处"表名与类名不一致"，下一个人会再问一遍同样的问题。**这一步代价最大，需要你明确是否接受。**

**完成后**：`flyway-conventions` 7 → 4 条（V1/V3 消除），继续推进 V2/V4/V5。

---

## 4. 需要你裁决

| # | 决策 | 建议 |
| --- | --- | --- |
| **G1** | 后台控制台域的域词 | **`console_`**（备选 `admin_`） |
| **G2** | `saa_admin_account` / `iam_menu` 是否按上表改名 | 是（一次修掉 V1+V3+V6） |
| **G3** | PO 类名是否一并改 | 建议改（否则表名/类名长期不一致）；若求稳可只改注解 |
| **G4** | V2/V4/V5 的处置（`open` 登记 / `idaas` 登记并收敛 `group` / V6 消歧） | 我建议：`open` 登记为**开放平台**域、`idaas` 登记、`group` 留档；`saa` 消歧后 V6 自然消除 |

> 其余三项（`open`/`idaas`/`group`）按你说的"参考我的建议"：**`open` 登记 + 写明"开放平台"义**（可选改名 `openplat_` 彻底消歧）、**`idaas` 登记**、**`group` 收敛留档**。这三项都是**登记为主、零或极小改名**。
