# 统一规范整改总纲（待决策）

> 目的：按"整个工程统一规范"的目标，把**所有要改的地方、改法、以及需要你决策的点**列全，供你逐项裁决。
> 原则：**先列全 → 你决策 → 再执行**。本文件**未改动任何文件**（除本文件自身）。
>
> 依据：`docs/ENGINEERING_RULES.md`（项目工程规则）、`docs/standards/00` 总纲（规范与项目材料分离）、`docs/business/ACCOUNT_PERMISSION_MODEL.md`、`docs/business/PROJECT_FACTS_INVENTORY.md`（事实清单）。

---

## 〇、先讲清"名字的权威来源"（避免再选错目标名）

**应用的权威名字 = k8s 应用清单**（`k8s/local/applications.yaml` 的 `__APP_IMAGE_*__` 占位 + `k8s/ack/*.yaml` 的实际镜像）。实测清单：

| 类别 | 权威名 | 旧名（文档里仍在用） |
| --- | --- | --- |
| 官网 | `open-website` | — |
| 统一门户 | `unified-portal` | — |
| PC 管理端 | `pc-admin` | `gv_chat_desktop`？ |
| SaaS 后台 | `saas-admin` | `gv_saas_admin` |
| SaaS 移动端 | `saas-mobile` | `gv_saas_mobile`、`gv_chat_app`、`gv_chat` |
| 服务端总体 | （各 `*-service`；库 `im_server` / `open_im`） | `gv_im_server` |

> **旧名 → 新名的映射我按上表推断**，但 `gv_chat_desktop` → `pc-admin`、`gv_chat_app` → `saas-mobile` 属**我的推断**，需你确认（见 D5）。

---

## 一、命名空间与前缀（登记表 + 门禁）

| # | 要改的地方 | 现状 | 建议改法 | 决策 |
| --- | --- | --- | --- | --- |
| 1.1 | `ENGINEERING_RULES.md` 前缀登记表 | 缺"归属服务/限界上下文"信息 | 增列 **`业务线` + `限界上下文` + `权威服务`**；18 行逐行补全 | 已议，执行 |
| 1.2 | 新增前缀 `open` | 未登记，门禁报 3 张表 | 登记为 **开放平台（Open Platform，对第三方开放授权）**；注明"非开源" | **D-已定**（你已批准） |
| 1.3 | 新增前缀 `idaas` | 未登记，1 张表 | 登记为**集团认证服务（IDaaS）** | **D-已定** |
| 1.4 | `group`（2 张表） | 与 `idaas` 同域两名 | **收敛**：`group_account`→`idaas_account`、`group_org`→`idaas_org`；`group_` 留档 | **D2 待你选方向** |
| 1.5 | 新增前缀 `console` | 未登记 | 登记为**运营控制台域**（你已采纳） | **D-已定** |
| 1.6 | `iam` 行语义 | 名不符实（无 Identity Management） | **不改表名**，在登记表写明"只做授权"、权威服务=`platform-tenant-service`、身份由 `idt_`/`group_account`/`user_` 承担 | **D1 待你选** |

**门禁耦合**（务必遵守）：`validate-flyway-conventions.ps1` 校验"前缀已登记"与"已登记前缀必须出现在 `ENGINEERING_RULES.md`"→ **登记两处必须同改**；移除需**先删文档行、再删清单行**。

---

## 二、代码级改名

| # | 对象 | 现状 | 建议改法 | 量级（实测生产代码） | 决策 |
| --- | --- | --- | --- | --- | --- |
| 2.1 | 审计域表 | `iam_audit_log` / `iam_audit_idempotency` / `iam_audit_archive_manifest` | → `audit_log` / `audit_idempotency` / `audit_archive_manifest`（**用上已登记却空着的 `audit` 域**；审计是独立域、IM 与 SaaS 共用，已被实测证实） | java+sql 合计 168 处，**含索引名**、两个迁移根 | **A 已批准** |
| 2.2 | SaaS 后台账号表 | `saa_admin_account`（`saa` 是业务线名截断） | → `console_admin_account`；**并同步类名** `SaaAdminAccountPo` → `ConsoleAdminAccountPo`（你要求"保持一致"） | 表名 java 14 / sql 16；类名 56 | **C 已批准** |
| 2.3 | `iam_menu` | 在 `platform-admin-service` | **保留不动**（git 历史已证为 IAM 迭代的有意产物） | — | **已定** |
| 2.4 | `.env` | `MEDIA_PRIVATE_BUCKET=gv-im-local-private` | → `open-im-local-private`（与 k8s 其它 `open-*` 一致） | 1 处 | 待确认无既有数据依赖 |

**改名时必须同步的 5 类**（漏一类即运行期缺陷）：迁移 DDL（含 **索引名 `uk_/idx_<表名>_<语义>`**）、PO `@TableName`、代码内表名字符串、登记表、`table-prefixes.txt`。

---

## 三、数据库与 schema 命名

| # | 对象 | 文档写的 | 代码实际 | 建议 | 决策 |
| --- | --- | --- | --- | --- | --- |
| 3.1 | 默认库名 | `ENGINEERING_RULES.md:154`：`gv_im` | IM 侧 `im_server` | **改文档 = 代码**（`gv_im`→`im_server`） | **D3 待选** |
| 3.2 | SaaS 库名 | `ENGINEERING_RULES.md:155`：`gv_saas` | `open_im`（11 个服务） | 同上（`gv_saas`→`open_im`） | **D3 待选** |
| 3.3 | 审计库名 | 旧执行记录（2026-09-19）写 `gv_audit` | `open_audit` | 改那份**过时执行记录**；代码无异议 | 执行 |
| 3.4 | 库名语义倒挂 | — | **IM 用中性名 `im_server`，SaaS 用产品名 `open_im`** | 需口径：是否统一（如 `open_im`/`open_saas`，或 `im_server`/`saas_server`） | **D4 待选** |
| 3.5 | media 的 Flyway history | `support_schema_history`（沿用旧前缀 `support`） | 同左 | 是否改名需评估（**history 表改名影响既有库 Flyway 状态**） | **D6 待选** |

---

## 四、文档层改名（旧产品名与绝对路径）

### 4.1 旧产品名（**活文档**，需改）

| 文件 | 处数 | 旧名 |
| --- | --- | --- |
| `docs/standards/16_CURRENCY_CONVENTIONS.md` | 3 | `gv_saas_admin`、`gv_saas_mobile` |
| `docs/standards/43_E2E_TESTING_CONVENTIONS.md` | 4 | `gv_im_server`、`gv_chat_app`、`gv_chat_desktop` |
| `docs/standards/15_CLIENT_RELEASE_SKILL.md` | 2（+4 绝对路径） | `gv_chat_app`、`gv_im_server` |
| `docs/business/CONVERSATION_TYPES_AND_PRIVACY.md` | 1 | `gv_chat` |
| `docs/compliance/CHINA_COMPLIANCE.md` | 1 | `gv_chat` |
| `docs/compliance/P0_REMEDIATION_PLAN.md` | 3 | `gv_chat_app` |
| `docs/features/SECRET_CHAT_01_E2EE.md` | 1 | `gv_chat_app` |
| `docs/services/*/README.md`（2 个） | 2 | `gv_saas_admin` |
| `docs/im-services/*/README.md`（11 个） | ~20 | `gv_saas`（**这些文件在 `im-services/` 下却写 SaaS 库名，命名归属本身也乱**） |
| `docs/im-services/audit/README.md` | 4 | `gv_audit`（过时，实际 `open_audit`） |
| `docs/deployment/ACK.md` | 8 | `gv_audit`、`gv_saas` |

> ⚠️ `docs/im-services/<域>/` 目录本身命名的也是"im-services"，但里面多是 **SaaS 服务**（identity/customer/payment/tenant/marketing/resource/sms）——**目录归属与业务线不符**。这可单独作为一项（见 D7）。

### 4.2 绝对路径（`standards/10` 明确禁止"绑定仓库名/路径"）

- **活文档**：`docs/standards/15_CLIENT_RELEASE_SKILL.md`（4 处）、`docs/standards/43_E2E_TESTING_CONVENTIONS.md`（1 处）、`docs/RELEASE_RUNBOOK.md`（**15 处**）、`docs/deployment/ACK.md`（1 处）
- **日期快照**（保留）：`docs/audit-2026-09-17-*.md`（8 处）、`docs/renovation/*`（多份方案稿）

### 4.3 保留不改（历史事实）

- `docs/renovation/*` 与日期化报告里的旧名/路径 —— **历史留档**，改了等于篡改证据。
- `im-user-service` 迁移里的 `gvchat://oauth/callback` → `openchat://oauth/callback` —— **迁移语句本身**。

---

## 五、需要你决策的清单（汇总）

| # | 决策 | 选项 | 我的建议 |
| --- | --- | --- | --- |
| **D1** | `iam` 是否改名 | ①不改，只在登记表写明语义 ②改（`authz_`/`tntz_`/`access_`） | **①不改**（行业惯例；167 处生产改动+索引名；集团层将来可另取新名） |
| **D2** | `group` 收敛方向 | ①收敛到 `idaas`（改 2 张表）②收敛到 `group`（改 `idaas_client` 1 张） | **①收敛到 `idaas`**（与服务名/包名一致；`group` 是组织层级+SQL 保留字） |
| **D3** | 库名对齐方向 | ①**改文档 = 代码**（`gv_im`→`im_server`、`gv_saas`→`open_im`，零数据迁移）②改代码 = 文档（需库迁移） | **①改文档**（零风险） |
| **D4** | IM/SaaS 库名语义是否统一 | ①保持（IM `im_server` / SaaS `open_im`）②统一风格（如 `open_im` + `open_saas`） | 需你定；若选②则**涉及既有库改名** |
| **D5** | 旧客户端名新名映射确认 | 见 §〇 表：`gv_chat_desktop`→`pc-admin`、`gv_chat_app`/`gv_chat`→`saas-mobile`、`gv_saas_admin`→`saas-admin`、`gv_im_server`→? | 请确认或纠正 |
| **D6** | `support_schema_history` 是否改名 | ①改（需评估 Flyway 状态）②不改（留档） | **②暂不改**，单列待评估 |
| **D7** | `docs/im-services/<域>/` 目录归属 | ①把 SaaS 服务的文档移到 `docs/services/` 或 `docs/platform-services/` ②保持 | 需你定（属文档结构治理） |
| **D8** | 日期快照与 renovation 文档里的旧名 | ①保留 ②加"历史文档，命名以最新为准"标注 | **②加标注**（保留内容、说明时效） |

---

## 六、执行顺序（决策后）

| 步 | 动作 | 依赖 |
| --- | --- | --- |
| 1 | 改 `ENGINEERING_RULES.md`：增列 + 条文 + 新增 `open`/`idaas`/`console` 行 + 库名对齐 | D1–D4 |
| 2 | 同步 `scripts/validate/table-prefixes.txt`（两处同改） | 步 1 |
| 3 | **A**：`iam_audit_*` → `audit_*`（DDL 含索引名 + 两迁移根 + 文件名） | 已批准 |
| 4 | **C**：`saa_admin_account` → `console_admin_account`（表名 + PO 类名 + 索引名 + 注解） | 已批准 |
| 5 | **D2**：`group_*` → `idaas_*` | D2 |
| 6 | 文档层改名（standards / business / compliance / services / deployment） | D5、D8 |
| 7 | `.env` 桶名；过时执行记录里的 `gv_audit` | — |
| 8 | 跑 `validate-flyway-conventions.ps1` 确认 **7 → 0**；该门禁转阻断 | 步 1–5 |
| 9 | 每步后跑受影响模块 `mvnw --offline -B -ntp -pl <模块> -am test` | — |

> ⚠️ 全部改名**必须在打冻结 tag 之前**完成，否则只能新增迁移版本做 RENAME（你已说冻结不急）。
> ⚠️ 注意 `target/classes` 会残留旧文件（见 `ENGINEERING_GOVERNANCE_03` §3.2 教训），验证前需 `clean package`。
