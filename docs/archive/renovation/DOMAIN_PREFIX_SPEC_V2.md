> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/renovation/DOMAIN_PREFIX_SPEC_V2.md

# 领域与表前缀规范 v2（业务线 → 域 → 实体）

> ⛔ **本文件已被 [DOMAIN_PREFIX_SPEC_V3.md](./DOMAIN_PREFIX_SPEC_V3.md) 取代，仅作留档。**
>
> 用户在审阅中指出本文件两处错误：① `open` 表示整个工程开源，**没有语义可重定义**；② 本文件建议 `iam_menu` → `saas_menu`，等于**把业务线名写进表前缀**，与 4.2 自相矛盾。v3 已重新设计，请以 v3 为准。

> 本文件是 **v2 细化稿**，取代 `DOMAIN_PREFIX_SPEC_REVIEW.md` 的"建议"章节。**未改动任何代码或规范**。
>
> v2 的由来：你指出 `open` 表示开源、用作领域不合适，并提出 **`业务-领域-xxx`** 的分层。这个判断是对的，本文件把它整理成可长期遵守的规范，再用它重新审视现有问题。

---

## 一、为什么旧口径会出问题（先把根因说清）

现状把**四个不同维度**混在同一层级的前缀里：

| 维度 | 例子 | 应放在哪 |
| --- | --- | --- |
| 业务线 | IM、SaaS、集团 | **命名空间/库/代码根**，不是表前缀的第一段 |
| 限界上下文（域） | order、payment、tenant、user | **表前缀**（这一层才是前缀该表达的东西） |
| 数据判别维度 | 业态（KTV/酒店/SPA/零售）、租户、环境 | **列 + 约束**，不是命名 |
| 项目属性 | 开源（open）、内部、通用 | **哪一层都不该有**——零区分度 |

`open`（开源）属第四类：本项目整体开源，它对**每一张表**都成立，因此**无法区分任何两样东西**。同理不可有 `free_`、`oss_`、`common_`、`core_`。

**判据（建议写进规范）**：把某个词放到前缀里，问一句"**去掉它，还能不能唯一定位到这个域？**"——`open` 去掉后不影响任何定位，所以它不配当前缀。

---

## 二、建议的分层（三层，各司其职）

```
业务线 (business line)     ← 命名空间：库名 / 代码根 / 部署边界
   └── 域 (bounded context) ← 表前缀：命名空间内的域标识
         └── 实体 (entity)  ← 表名主体：单数业务名词
```

### 第 1 层：业务线＝命名空间，**不写进表名**

本仓实际上**已经有了**这一层，而且做得对：

| 业务线 | 代码根 | 库 | 依据 |
| --- | --- | --- | --- |
| IM | `im-services/` | `im_server` | `ENGINEERING_RULES.md:155` |
| SaaS | `platform-services/` | `open_im`（规范称 SaaS 库 `gv_saas`） | 同上 |
| 通用支撑 | `common-services/` | 跟随调用方 | 同上 |
| 集团 | `group-services/` | 跟随 | 现有 `group-idaas-service` |

**关键建议：第 1 层保持"只体现在命名空间"，不要变成表名前缀。** 理由：

1. 业界（DDD + 模块化单库）通行做法是**一个限界上下文一份命名空间**，而不是"组织层级 + 上下文"两段。两段前缀会让表名普遍变长，而 `:156` 明确说"缩写的目的在于控制表名长度"。
2. **没有它也不会歧义**：`im_user_*` 与 `saas_*` 本来就不重名——已实测全部 22 个前缀中，**没有任何一个前缀跨业务线重复**（`adm` 是 im-admin 专用；`platform-admin-service` 用的是 `iam_`/`saa_`，**不用 `adm_`**）。
3. 真正需要表达业务线的地方是**登记表**和**库/代码结构**——那才是一次性、可评审、不增加表名长度的地方。

> 若你仍希望表名显式带业务线，那就是"两段前缀"方案（`im_user_*`、`saas_ord_*`），代价是 22 个前缀全部改名 + 所有 SQL/PO/索引名同步改。**本文件按你的三层结构，建议第 1 层走命名空间、第 2 层走表前缀**——结构是三层，**表名只承载第 2、3 层**。

### 第 2 层：域＝表前缀（这才是前缀的职责）

规则：
- **2–4 个小写字母**（可含 1 个下划线），全局唯一；
- **一域一前缀**，禁止一域多前缀、也禁止一前缀多域；
- 取自**领域词汇**，不取自业务线名、不取自技术分层（`biz`/`svc`/`core`/`api` 皆不可）；
- 前缀登记后**语义冻结**（`:182`）。

### 第 3 层：实体＝表名单数业务名词

- `snake_case`、**单数**、业务主体在前（`:183-184`）；关联表如 `user_friend`。
- **业态、租户、环境、状态是列，不是命名维度。** 已实测：`business_type varchar(32) COMMENT '业态 KTV/HOTEL/SPA/RETAIL'` 出现在 order 域 **15 张表**上，取值来自 `tnt_business_type` 数据表（`('KTV','KTV','ACTIVE',…)`）。
  → 所以 **KTV / 酒店 / 足浴 / 零售 不各自建域、不各自取前缀**，它们是 `business_type` 的取值，共用 `ord_` 域，**靠列区分**。这正是你问的"saas 内再分 ktv、酒店，有没有结构能区分"——**有，而且是列，不是前缀**。

> 这一条很重要：如果每个业态一个域，`ord_` 会裂成 `ktv_`/`hotel_`/`spa_`/`retail_`，而它们的订单/履约/计价模型高度共享（实测代码里 `KtvPricingPlan` 就在 `domain.ktv` 包内、与 `/business/orders` 共用一份计价）。业界的做法是**一个域 + 业态判别列**（多态/单表继承）。

---

## 三、业界参考要点（供你判断，非教条）

1. **优先用 schema/库做隔离，而不是靠表名前缀**：PostgreSQL/SQL Server 惯用 schema 隔离域；本仓 IM 与 SaaS **已经分库**（`im_server` / `gv_saas`），所以前缀只需承担**同库内的域区分**，不需要再背业务线。
2. **前缀登记表 + 门禁**：成熟工程普遍把"前缀 → 归属团队/上下文"做成**唯一权威表**并加 CI 校验。本仓 `:158` 已有表、门禁已有一半（查登记），**缺的是"归属"这一列**。
3. **禁止属性词前缀**：常见反模式 `core_`、`common_`、`util_`、`main_`、`open_`、`free_`——都无区分度。
4. **一域一前缀、一前缀一域**：业界名为 "namespace allocation"，冲突即缺陷。
5. **判别维度用列**：业态/类型/状态/租户用列 + 约束 + 索引，不制造域（Type Code、多态关联、单表继承皆属此列）。
6. **命名冲突规避**：避免 SQL 保留字作前缀（`group` 即 `GROUP BY` 的 `GROUP`），倾向更具体的域词。

---

## 四、细化后的规范条文（可直接并入 `ENGINEERING_RULES.md`）

> 下面是**建议条文**，措辞已按 `ENGINEERING_RULES.md` 既有风格写，待你确认后并入。

**4.1 表名结构**

> 表名 = `<域前缀>_<单数业务名词>`。**业务线不写进表名**，由库/代码根/部署边界承载（IM=`im_server`、SaaS=`gv_saas`/`open_im`、通用支撑与集团跟随调用方）。

**4.2 域前缀**

> - 2–4 个小写字母（可含 1 个下划线），**全局唯一**；
> - **一个域一个前缀**；一域多前缀或一前缀多域均属违规；
> - 必须取自**领域词汇**；不得取自业务线名、组织层级、技术分层，**尤其不得取自项目属性**（如"开源"）；
> - 判据：**去掉该词，表能否唯一定位到域**；不能则不合格；
> - 登记后语义冻结，变更属架构变更。

**4.3 判别维度**

> 业态、租户、环境、状态、类型等**一律用列表达**（配约束与索引），**不得因此新增域或前缀**。业态取值以 `tnt_business_type` 为准（如 `KTV`/`HOTEL`/`SPA`/`RETAIL`）。

**4.4 登记表增加"业务线"与"限界上下文/归属服务"两列**

> 登记表须记录每个前缀的**业务线**与**限界上下文/归属服务**；门禁校验前缀已登记且已文档化。**归属列是根治点**——它能让人评审"一个前缀是否只服务一个上下文"。

**4.5 跨域表**

> 若某服务必须建其他域的表，须在该域登记表中**显式标注为"跨域共享表"**并说明权威写方；否则视为违规。

**4.6 预留前缀**

> 允许登记"预留"前缀（尚无表使用），但须标注状态与预期启用批次；**预留前缀不得解释已在使用的其他前缀**（避免用"预留"掩盖偏差）。

---

## 五、按新规范重新审视现有问题

### 5.1 完整前缀归属表（建议并入登记表）

| 业务线 | 域（限界上下文） | 前缀 | 归属服务 | 表数 | 依据 |
| --- | --- | --- | --- | --- | --- |
| IM | User | `user` | `im-user-service` | 20 | L32 User 退回 IM 域 |
| IM | Open Platform（开放授权） | `open` | `im-user-service` | 3 | 实为 OAuth 2.0 授权服务器 |
| IM | Message | `msg` | `im-message-service` | 15 | |
| IM | Conversation | `conversation` | `im-conversation-service` | 8 | |
| IM | IM 后台 | `adm` | `im-admin-service` | 16 | L32 IM 后台独立、不在 SaaS 表内 |
| 通用支撑 | Media | `media` | `common-media-service` | 5 | |
| 通用支撑 | Mail | `mail` | `common-mail-service` | 3 | |
| 通用支撑 | SMS | `sms` | `common-sms-service` | 3 | |
| 通用支撑 | Audit | `audit`（预留） | `common-audit-service` | 0 | 实际用 `iam_audit_*` |
| 通用支撑 | Payment Channel | `paych`（预留） | `common-payment-channel-service` | 0 | 实际用 `pay_channel_*` |
| IM | 接入网关 | `ws`（预留） | `im-access-ws` | 0 | 无迁移根 |
| 集团 | 集团认证服务 IDaaS | `idaas` / `group` | `group-idaas-service` | 1 / 2 | 同一包 `io.openware.group.idaas` |
| SaaS | Identity | `idt` | `platform-identity-service` | 4 | L21 |
| SaaS | Tenant Access | `tnt` | `platform-tenant-service` | 9 | L22 |
| SaaS | Tenant Access·授权子域 | `iam` | `platform-tenant-service` | 6 | L30 权威在此 |
| SaaS | SaaS Admin | `saas`（待定） | `platform-admin-service` | 0 | 现用 `saa`/`iam_menu` |
| SaaS | Resource Scheduling | `res` | `platform-resource-service` | 5 | L23 |
| SaaS | Transaction Fulfillment | `ord` | `platform-order-service` | 17 | L24（含业态 KTV/HOTEL/SPA/RETAIL） |
| SaaS | Funds Collection | `pay` | `common-payment-service` | 11 | L25 |
| SaaS | Tenant Loyalty | `cst` | `platform-customer-service` | 9 | L26 |
| SaaS | Tenant Marketing | `mkt` | `platform-marketing-service` | 7 | L27 |

### 5.2 现有问题的处置（按新规范）

| # | 问题 | 新规范下的判定 | 建议处置 |
| --- | --- | --- | --- |
| P1 | `open` 表示"开源" | **不合格**（属性词，零区分度） | **不改名，但重新定义语义**：登记为 **Open Platform（开放授权 / Authorization Server）** 域，即"开放"= 对第三方开放，而非"开源"。这 3 张表确实是 OAuth 2.0 授权服务器（`OauthConsentController`、`/oauth/consent`、授权码） |
| P2 | `iam_` 被两个上下文共用 | **不合格**（一前缀多域） | **`iam_` 划定给 Tenant Access 的授权子域**（6 张表在此）；`platform-admin-service` 的 `iam_menu` **不属于 IAM**，应归 SaaS Admin 域 → 建议改名 `saas_menu`（**1 张表、1 处引用**，代价最小） |
| P3 | `saa` vs `saas` | `saa` 非领域词 | 登记 **`saas`** 为 SaaS Admin 域前缀，`saa_admin_account` 作历史口径留档（`:182` 禁止为缩写改名）；若你允许改名，则 `saa_admin_account`→`saas_admin_account`（14 处引用） |
| P4 | `group` + `idaas` 一域两名 | **不合格**（一域多前缀） | 收敛为 **`idaas`**（更具体、且 `group` 是 SQL 保留字）；`group_account`/`group_org` 作历史口径留档（3 张表、约 2 处引用） |
| P5 | 跨域建表 | 需显式标注 | `tnt_payment_method`（payment 建）与 `user_conversation_mute`（conversation 建）在登记表标为**跨域共享表**并注明权威写方；`adm_app_release*` 已退役、保留注释 |
| P6 | 3 个"登记了但无表" | 允许，但**不得用来解释其他前缀** | `audit`/`paych`/`ws` 标为**预留**并写预期启用条件；**不要删**——它们目前正是 `iam_audit_*`/`pay_channel_*` 偏差的留档载体，删了那两处会变成"未登记前缀" |
| P7 | 业态（KTV/酒店） | 新规范**明确不是域** | **不动**。保持 `ord_` 单域 + `business_type` 列（现状已正确，15 张表带此列） |

### 5.3 落地顺序

① 你确认第二/四节的分层与条文（尤其第 1 层是否走命名空间）→ ② 改写 `ENGINEERING_RULES.md`：新增"业务线"与"限界上下文"两列 + 4.1~4.6 条文 + 三行新登记 → ③ 同步 `table-prefixes.txt` → ④ 若同意 P2 的 `iam_menu`→`saas_menu`，一并做（最小代价修掉最严重偏差）→ ⑤ 跑 `validate-flyway-conventions.ps1` 确认 7 → 0 → ⑥ 该门禁转阻断。

---

## 六、需要你拍板的点

| # | 决策 | 选项 |
| --- | --- | --- |
| D1 | **第 1 层（业务线）是否写进表名** | ① **不写**（走库/代码根/登记表，表名只承载域+实体）**← 建议** ② 写（`im_user_*`/`saas_ord_*`，22 个前缀全改名） |
| D2 | `open` 域定名 | ① 登记为 **Open Platform（开放授权）**、`open` 保留作前缀 **← 建议** ② 改用 `oauth_`（3 表、7 处引用改名） ③ 并入 `user` 域 |
| D3 | `iam_menu` 归属 | ① 归 SaaS Admin、改名 `saas_menu`（1 表 1 引用）**← 建议** ② 归 Tenant Access、迁表到 tenant 服务 ③ 维持共用 `iam_` |
| D4 | `saa` → `saas` | ① 只登记 `saas`、`saa_*` 作历史口径留档 **← 建议** ② 改名 `saas_admin_account`（14 处） |
| D5 | `group`/`idaas` 收敛 | ① 收敛为 `idaas`、`group_*` 留档 **← 建议** ② 两个都登记 |
| D6 | 是否采纳 4.4（登记表加"业务线"+"限界上下文"两列） | 是 **← 建议** / 否 |
| D7 | 是否采纳 4.6（预留前缀不得解释其他前缀） | 是 **← 建议** / 否 |

> 我的建议都选"**登记 + 留档、最小改名**"：因为 `:182` 禁止为统一缩写改名，且本仓对 `audit_`/`paych_` **正是这么做的**。唯一强烈建议改名的是 **D3 的 `iam_menu`**——1 张表、1 处引用，却修掉了最严重的"一前缀多域"。
