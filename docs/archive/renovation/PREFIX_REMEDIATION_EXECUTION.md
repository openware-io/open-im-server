> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/renovation/PREFIX_REMEDIATION_EXECUTION.md

# 前缀整改执行规范（A / C / D）

> 经用户裁决：**A 可以**、**C 应保持一致**、**D**：`open` 登记（开放平台义）、`idaas` 登记、`group` 收敛留档。
> 本文件是**执行规范**；**尚未改动代码**。

---

## 0. 「收敛留档」是什么意思（用户提问）

`group-idaas-service` 现在用**两个前缀**指**同一个域**：

| 表 | 现前缀 |
| --- | --- |
| `group_account`、`group_org` | `group` |
| `idaas_client` | `idaas` |

违反已有原则"**一个域 = 一个前缀**"。两种收敛方向：

| 方向 | 结果 | 评价 |
| --- | --- | --- |
| **A. 收敛到 `idaas`** | `group_account`→`idaas_account`、`group_org`→`idaas_org`、`idaas_client` 不变 | 与服务名 `group-idaas-service`、包名 `io.openware.group.idaas` 一致；`idaas` 是**技术域词**（身份即服务） |
| **B. 收敛到 `group`** | `idaas_client`→`group_client` | `group` 是**组织层级**（集团），而 `idaas_client` 是 OAuth 客户端、不是集团组织实体；且 `group` 是 SQL 保留字 |

**「留档」的意思**：不删除旧名字的历史记录，而是在**登记表 + 项目事实清单**里写明"`group_*` 是历史口径，已收敛到 `idaas_*`"，让后人能查到为什么会有两个名字。（本仓既有先例：`audit`/`paych` 两个预留前缀就是照原样留档、不改名。）

→ **建议方向 A**（收敛到 `idaas`，`group_*` 留档）。

---

## A. `iam_audit_*` → `audit_*`（审计域归位）

**架构依据（已实测）**：审计是**独立域**，被 **IM 与 SaaS 共同调用**——

| 证据 | 实测 |
| --- | --- |
| 独立契约模块 | `common-services/audit/common-audit-api`（对外发布 `AuditClient`） |
| 独立 schema | `gv_audit`（[ACK.md](../../deployment/ACK.md) §审计存储） |
| 独立 Flyway history | `flyway_schema_history_audit` |
| 调用方分布 | `AuditClient`：SaaS **881** 处、IM **23** 处、`sdk` 51 处、`common-services` 178 处 |

→ 审计既然自成一域、且两业务线共用，其表就该用**已登记却空着的 `audit` 前缀**。

**改名清单**：

| 现名 | 新名 |
| --- | --- |
| `iam_audit_log` | `audit_log` |
| `iam_audit_idempotency` | `audit_idempotency` |
| `iam_audit_archive_manifest` | `audit_archive_manifest` |

**执行面（实测引用数，含索引名，漏一处即出错）**：

| 位置 | `iam_audit_log` | `iam_audit_idempotency` | `iam_audit_archive_manifest` |
| --- | --- | --- | --- |
| `*.java` | 32 | 20 | 16 |
| `*.sql` | 38 | 12 | 3 |
| 其他（yml/md/ps1/xml） | 60 | 18 | 14 |
| **合计** | **130** | **50** | **33** |

**特别注意**：
1. **索引/约束名**同样带 `iam_audit_` 前缀（`V1__iam_audit_baseline.sql:56-63`、`81-83` 等），规范要求 `uk_/idx_<表名>_<语义>`，**改表名必须同步改索引名**。
2. **`flyway_schema_history_audit` 不改**——它是 history 表名，不是域表，改名会影响既有库的 Flyway 状态。
3. 两个迁移根都要改：`db/migration`（老根，V1）与 `db/migration-audit`（新根，V1/V2/V3）。
4. 迁移**文件名**（如 `V1__iam_audit_baseline.sql`）建议一并改为 `V1__audit_baseline.sql`——**必须在冻结 tag 之前**，否则改名等于改已冻结迁移。
5. 我的分析文档（`docs/renovation/*`）里的旧名**不改**——那是历史记录。

---

## C. `saa_admin_account` → `console_admin_account`（保持一致）

**用户裁决："应该保持一致"** → 即**表名与 PO 类名一并改**，不留"表名/类名不一致"。

| 类别 | 现名 | 新名 | 引用数 |
| --- | --- | --- | --- |
| 表 | `saa_admin_account` | `console_admin_account` | java 14 / sql 16 |
| PO 类 | `SaaAdminAccountPo` | `ConsoleAdminAccountPo` | **56** |
| 仓储实现 | `SaaAdminAccountRepositoryImpl` | `ConsoleAdminAccountRepositoryImpl` | 随类名 |
| 索引名 | `uk_saa_*` / `idx_saa_*` | `uk_console_*` / `idx_console_*` | 5 处（实测） |
| PO 注解 | `@TableName("saa_admin_account")` | `@TableName("console_admin_account")` | 1 |

**域名**：`console`（用户已采纳）。`iam_menu` **保持不动**（§3.1 已证为 IAM 迭代的有意结果）。

---

## B. `iam_` 是否改名——深入分析（用户要求）

### B.1 现状（实测）

| 层面 | 身份（账号/凭据） | 授权（RBAC） | 登录/SSO |
| --- | --- | --- | --- |
| **IM 业务线** | `user_*` **20 张**（含 `user` 主表） | 无 | `im-user-service`（IM 账号认证） |
| **SaaS 业务线** | `idt_*` **4 张**（`idt_account`/`idt_login_identity`/`idt_oauth_link`/`idt_profile_sync_record`） | `iam_*` **6 张**（`iam_role`/`iam_permission`/`iam_role_permission`/`iam_user_role`/`iam_approval`/`iam_consumer_application`） | `/admin/auth`（`AdminLoginController`/`SsoAuthController`/`AdminSessionController`） |
| **集团层** | `group_account`/`group_org` | **无**（`Role`/`Permission`/`Grant`/`Policy` 全 0） | `/login`/`/session`/`/logout`/`/sso-ticket` + `idaas_client` |

**关键事实（本轮新测）**：

1. **`iam_` 下无任何身份/凭据表**——身份的权威全在别处。所以前缀里的 **I（Identity）缺失**。
2. **`iam_` 全部 6 张表都是租户作用域**：`iam_role`/`iam_user_role`/`iam_consumer_application`/`iam_approval` **带 `tenant_id`**；`iam_permission`/`iam_role_permission` **不带**（全局参考数据）。→ 它属 **SaaS 租户层**，**不属集团层**。
3. **集团层不感知租户**：`group-services` 里 `tenant_id` 出现 **0 次**。→ **集团层在结构上无法承载租户作用域的授权**。
4. **`iam_` 不出现在对外契约**（`*-api` 模块 0 处）→ 改名**不破坏跨服务契约**。
5. **改动代价（精确区分生产与文档）**：

   | 表 | 生产代码（java+sql+yml+xml） | 文档 |
   | --- | --- | --- |
   | `iam_role` | 53 | 42 |
   | `iam_user_role` | 41 | 37 |
   | `iam_permission` | 32 | 66 |
   | `iam_role_permission` | 22 | 20 |
   | `iam_consumer_application` | 11 | 16 |
   | `iam_approval` | 8 | 12 |
   | **小计（访问控制 6 张）** | **167** | 193 |
   | `iam_menu`（不动） | 7 | 97 |

   → **生产代码约 167 处**（另 6 张表的索引名同改）。

### B.2 结论：**本次不改名**（理由四条）

| # | 理由 |
| --- | --- |
| 1 | **"I" 的缺失是命名不精确，但未造成运行期缺陷**。`iam_` 是业界对 RBAC 的通行称呼（Java 生态有 Spring Security、Keycloak 等的广泛先例），不会让工程师误解表的用途。 |
| 2 | **本仓规范倾向最小改动**：`ENGINEERING_RULES.md:182` 禁止"仅为统一缩写而重命名"；`audit`/`paych` 两个预留前缀都照原样留档未改名。改名 `iam_` 属"更精确"而非"必须"，先例不支持。 |
| 3 | **确有 167 处生产改动 + 索引名**，而收益是命名更准；**风险/收益不划算**，且改错会引入运行期缺陷。 |
| 4 | **集团层将来若上收授权，可另取新前缀**（如 `giam_`），**不必把 `iam_` 空出来**——所以"为将来腾名字"这个理由不成立。 |

### B.3 替代方案（推荐执行，比改名更有价值）

**在登记表与事实清单里写实语义**，而**不改表名**：

> `iam` 行建议写法：**领域＝访问控制（授权）**；职责＝角色、权限、作用域授权、授权审批；**权威服务＝`platform-tenant-service`**（租户作用域）；**说明１**＝前缀名沿行业习惯取 IAM，但本域**只做授权（Access Management）**，身份由 `idt_*`（SaaS 平台账号）、`group_account`（集团）、`user_*`（IM）分别承担；**说明２**＝`iam_menu` 在 `platform-admin-service`（控制台呈现，IAM 迭代产物）；**说明３**＝`iam_audit_*` 已迁出至 `audit_*`（见 A）。

→ 这样"谁做身份、谁做授权、`iam_` 到底管什么"**第一次被写清楚**，而**零运行期风险**。

### B.4 若你仍决定改名——建议目标名

| 候选 | 评价 |
| --- | --- |
| **`authz_`** | 最准（authorization）；但 `authz` 不如 `iam` 通用 |
| `tntz_` | 显式表达"租户作用域"，与 `tnt_` 同族；但 4 字母+下划线偏长 |
| `access_` | 直白；但 `access` 泛化，区分度弱 |

**我的建议顺序：先做 B.3（零风险、收益最大）→ 将来有强诉求再按 B.4 改名。**

---

## D. `open` / `idaas` 登记 + `group` 收敛

| 前缀 | 处置 | 登记表写法 |
| --- | --- | --- |
| `open` | **登记** | 领域 **Open Platform（开放平台/开放授权）**；职责：第三方应用注册、scope、OAuth 用户授权（`im-user-service` 的 `openplatform` 子域）；**注明"open 指开放平台，非开源"**——本项目整体开源，"开源"无区分度 |
| `idaas` | **登记** | 领域 **集团认证服务（IDaaS）**；职责：集团账号、组织、SSO、会话、JWT 签发（`group-idaas-service`）；`group_*` 收敛留档 |
| `group` | **收敛到 `idaas` 后留档** | 在 `idaas` 行注明："历史口径 `group_account`/`group_org` 已收敛为 `idaas_account`/`idaas_org`" |

**命名空间影响**：`table-prefixes.txt` 需**新增 `open`、`idaas`、`console` 三个前缀**（`audit` 已存在）；`ENGINEERING_RULES.md` 登记表需**同增三行 + `iam` 行注明审计已迁出**。按门禁耦合，**两处必须同改**。

---

## 执行顺序（建议）

| 步 | 动作 | 说明 |
| --- | --- | --- |
| 1 | 改登记表 + `table-prefixes.txt`（新增 `open`/`idaas`/`console`，`audit` 行更新，`iam` 行注明） | 先让规范与门禁认新前缀 |
| 2 | **A**：`iam_audit_*` → `audit_*`（含索引名、两迁移根、文件名） | 消除"一前缀多域" |
| 3 | **C**：`saa_admin_account` → `console_admin_account`（表名 + PO 类名 + 索引名 + 注解） | 保持一致 |
| 4 | **D**：`group_*` → `idaas_*`（`idaas_client` 不变） | 一域一前缀 |
| 5 | 跑 `validate-flyway-conventions.ps1` 确认 **7 → 0** | 门禁 |
| 6 | 该门禁从 `report-only-gates.txt` 删除并转阻断 | 批次 2 收口 |

> 每步后跑 `mvnw --offline -B -ntp -pl <模块> -am test` 验证，并注意 `target/classes` 残留（见 03 方案 §3.2 的教训）。
