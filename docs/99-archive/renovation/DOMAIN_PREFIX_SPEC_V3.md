> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/renovation/DOMAIN_PREFIX_SPEC_V3.md

# 领域与表前缀规范 v3（重新设计）

> 取代 v1（`DOMAIN_PREFIX_SPEC_REVIEW.md`）与 v2（`DOMAIN_PREFIX_SPEC_V2.md`）的建议章节。**未改动任何代码或规范。**
>
> v3 的由来：你指出我两处错误——① `open` 表示**整个工程开源**，不该（也无法）当领域；② 我把 `iam_menu` 改成 `saas_menu`，等于**把业务线名写进表前缀**，自相矛盾。本文件重新设计，并把最关键的一处不确定性反向交给你。

---

## 一、我先纠正的两处错误

| 我的错误 | 为什么错 |
| --- | --- |
| 建议 `open` "不改名、重定义为 Open Platform" | `open` 表示**整个工程开源**——它对**每一张表**都成立。**没有语义可重定义**：一个对全集恒为真的词，无法区分任何东西。这不是措辞问题。 |
| 建议 `iam_menu` → `saas_menu` | `saas` 是**业务线名**，把它写进表前缀**直接违反**我自己写的"前缀取自领域词汇、不取自业务线"。自相矛盾，你的判断准确。 |

---

## 二、设计原则：**概念的层级 = 名字的层级，一一对应，不重复**

这是整个规范的骨架。业界把"层级混用"视为命名反模式，因为**同一件事在两个层级各说一遍**，必然产生冲突（我不停踩的坑正是这个）。

| 概念层级 | 数量关系 | 在本仓的载体 | 是否进表名 |
| --- | --- | --- | --- |
| **业务线 / 部署边界** | 1 对多域 | `im-services/`、`platform-services/`、`common-services/`、`group-services/` + 库名 | **否**（已由目录 + 库承载） |
| **限界上下文（域）** | 1 对多表 | **表前缀** | **是** |
| **实体** | 1 张表 | 表名主体（单数业务名词） | **是** |
| **判别维度**（业态/租户/环境/状态/作用域） | 多对多 | **列** | **否** |

**推论（三条硬规则）**

1. **一个概念只在一个层级表达**。业务线已在目录+库，就**不得**再进表名前缀。
2. **前缀 = 域名**，一一对应：一域一前缀、一前缀一域。
3. **判别维度一律用列**，**不得**因此新建域或前缀。

---

## 三、规范条文（建议并入 `ENGINEERING_RULES.md`，可直接用）

**3.1 表名结构**
> 表名 = `<域前缀>_<单数业务名词>`（`snake_case`，业务主体在前）。**业务线不写进表名**——它由代码根与库名承载（IM=`im-services/`、SaaS 平台=`platform-services/`、通用支撑=`common-services/`、集团=`group-services/`）。

**3.2 域前缀**
> - **2–4 个小写字母**（可含 1 个下划线），全局唯一；
> - **一域一前缀、一前缀一域**；一域多前缀或一前缀多域均属违规；
> - 必须取自**限界上下文名**（领域词汇）；
> - **不得**取自：业务线名（`im`/`saas`/`platform`）、组织层级、技术分层（`biz`/`core`/`svc`/`api`/`util`）、**项目属性**（`open`/`free`/`oss`/`common`）；
> - **判据**：把该词去掉，表能否**唯一**定位到一个域？不能则不合格；且该词不得对**全部**表成立；
> - 登记后语义冻结，变更属架构变更。

**3.3 判别维度**
> 业态、租户、环境、状态、作用域、类型**一律用列**表达（配约束与索引），**不得**因此新增域或前缀。业态取值以 `tnt_business_type` 为准。

**3.4 登记表须含"业务线""限界上下文""归属服务"三列**
> 前缀登记表要写明每个前缀属于**哪个业务线、哪个限界上下文、由哪个服务权威写入**。门禁校验"已登记 + 已文档化"；**归属列**用于人工评审"一前缀一域"。

**3.5 跨域表**
> 服务若必须建其他域的表，须在登记表**显式标注"跨域共享表"并写明权威写方**。

**3.6 预留前缀**
> 允许登记尚无表的预留前缀，须注明状态与预期启用条件；**预留前缀不得用来解释已在使用的其他前缀**。

---

## 四、按新规范重新推导的域与前缀（全部 22 个）

> 判定方法：**只看"这张表归哪个限界上下文"，不看它建在哪个服务、也不看业务线**。

| 业务线 | 限界上下文（域） | 前缀 | 权威服务 | 表数 | 备注 |
| --- | --- | --- | --- | --- | --- |
| IM | User | `user` | `im-user-service` | 20 | L32：User 退回 IM 域 |
| IM | **开放授权** | ⚠️ **待你定义**（见 §五） | `im-user-service` | 3 | `open_application*`、`open_user_authorization`；实为 OAuth 2.0 授权服务器 |
| IM | Message | `msg` | `im-message-service` | 15 | |
| IM | Conversation | `conversation` | `im-conversation-service` | 8 | |
| IM | IM 运营后台 | `adm` | `im-admin-service` | 16 | L32：IM 后台独立、不在 SaaS 表内 |
| 通用支撑 | Media | `media` | `common-media-service` | 5 | |
| 通用支撑 | Mail | `mail` | `common-mail-service` | 3 | |
| 通用支撑 | SMS | `sms` | `common-sms-service` | 3 | |
| 通用支撑 | Audit | `audit`（预留） | `common-audit-service` | 0 | 实际用 `iam_audit_*` |
| 通用支撑 | Payment Channel | `paych`（预留） | `common-payment-channel-service` | 0 | 实际用 `pay_channel_*` |
| IM | 长连接接入 | `ws`（预留） | `im-access-ws` | 0 | 无迁移根 |
| 集团 | 集团认证服务 | `idaas` | `group-idaas-service` | 1 | 现另有 `group_*` 2 张（见 P4） |
| SaaS | Identity（平台账号/登录） | `idt` | `platform-identity-service` | 4 | L21 |
| SaaS | Tenant Access（租户/组织/门店/配置） | `tnt` | `platform-tenant-service` | 9 | L22 |
| SaaS | **访问控制**（角色/权限/授权/审批） | `iam` | `platform-tenant-service` | 6 | L30 授权权威在 Tenant Access |
| SaaS | Resource Scheduling | `res` | `platform-resource-service` | 5 | L23 |
| SaaS | Transaction Fulfillment | `ord` | `platform-order-service` | 17 | L24；业态 KTV/HOTEL/SPA/RETAIL 是**列** |
| SaaS | Funds Collection | `pay` | `common-payment-service` | 11 | L25 |
| SaaS | Tenant Loyalty | `cst` | `platform-customer-service` | 9 | L26 |
| SaaS | Tenant Marketing | `mkt` | `platform-marketing-service` | 7 | L27 |
| SaaS | **运营后台（Console）** | ⚠️ **待定**（见 P2） | `platform-admin-service` | 2 | `saa_admin_account`（运营人员账号）、`iam_menu`（菜单/导航） |

---

## 五、⚠️ 必须由你定的关键点：那 3 张表到底是哪个域

这是全篇唯一的**实质不确定性**，我不替你定。

**事实**（实测，无歧义）：
- 3 张表是 `open_application`、`open_application_scope`、`open_user_authorization`，建在 `im-user-service`。
- 代码显示这是**完整的 OAuth 2.0 授权服务器**：`OauthConsentController`、`@RequestMapping("/oauth/consent")`、scope 勾选、签发授权码、`open_user_authorization` 记录用户对应用的授权。

**你的定义与事实在这里对不上**：
- 你说 `open` = **整个工程开源** → 那么 `open_application` 读作"开源应用"，**语义错误**（它其实是"第三方注册的应用"）。
- 但若 `open` = **"开放平台/开放授权"** → 它是一个**正当的限界上下文名**（微信开放平台、GitHub OAuth Apps 即此类），前缀**可以保留**。

**所以问题不在前缀写法，而在"这个域叫什么"**。三个选项：

| 选项 | 做法 | 代价 |
| --- | --- | --- |
| **A** | 保留 `open_`，但登记表**明确写清它是"开放授权（Open Authorization）"域**，与"开源"无关 | **0 改名**。风险：`open` 一词在本仓仍有"开源"歧义 |
| **B** | 改名 `oauth_`（`oauth_application`、`oauth_application_scope`、`oauth_user_authorization`） | 3 张表、**7 处 Java 引用**（`open_application` 5 + `open_user_authorization` 2） |
| **C** | 并入 User 域（`user_application` 等） | 3 张表改名，且会把"第三方应用治理"混进"IM 账号"域，语义变差 |

**我的建议：B（`oauth_`）**。理由：① `open` 在本仓已被你定义为"开源"，同一词兼两义必然反复误读；② `oauth_` 是自解释的域词，不存在歧义；③ 代价仅 7 处引用，是全表里第二小的改动；④ OAuth 授权服务器在业界是**一等公民上下文**，独立成域比并进 User 更符合 DDD。

---

## 六、现有问题逐条处置（P1–P7）

| # | 问题（实测） | 新规范下的判定 | 建议 |
| --- | --- | --- | --- |
| **P1** | `open` 兼"开源"与"开放授权" | 词义冲突 | 见 §五（建议改 `oauth_`，7 处引用） |
| **P2** | `iam_` 被两个上下文共用：`platform-tenant-service` 建 6 张授权表；`platform-admin-service` 建 `iam_menu`（**菜单/导航模型**，列为 `code`/`name`/`path`/`icon`/`i18n_key`/`sort_no`/`scope_level` + `required_permission`/`business_type`/`domain_code`） | **一前缀两域，最严重** | 见下方"P2 专项" |
| **P3** | `saa_admin_account`：`saa` 非域词，且它**不是"业务线"名**——该表是**运营人员账号**（含 `idaas_subject` 映射集团账号、`platform_account_id` 映射 `idt_account`、`role`=SUPER_ADMIN/PLATFORM_ADMIN/TENANT_ADMIN） | `saa` 不合格（截断且非域词） | 与 P2 合并处理（同属"运营后台"域） |
| **P4** | `group` + `idaas` 一域两名 | **一域多前缀** | 收敛为 `idaas_`（`idaas` 更具体；`group` 还是 SQL 保留字）；`group_account`/`group_org` 留档（各 1 处引用） |
| **P5** | 跨域建表：`tnt_payment_method`（payment 建）、`user_conversation_mute`（conversation 建） | 需显式标注 | 登记表标"跨域共享表"+ 权威写方 |
| **P6** | `audit`/`paych`/`ws` 登记了但 0 表 | 允许（预留） | 标预留；**不要删**——它们是 `iam_audit_*`/`pay_channel_*` 偏差的留档载体 |
| **P7** | 业态（KTV/酒店/SPA/零售） | **不是域** | **不动**。`ord_` 单域 + `business_type` 列（15 张表带此列），现状已正确 |

### P2 专项：`iam_menu` 与 `saa_admin_account` 的归属

**关键认知**：`platform-admin-service` 是设计文档 L28 定义的 **SaaS Admin BFF（"不拥有上述任一领域权威写表"）**，但它实际建了 2 张表。这 2 张表**不是 IAM 授权模型**，而是**运营后台自己的东西**：

- `saa_admin_account` = **运营人员账号**（登录、BCrypt 哈希、绑定 IM 账号/集团 IDaaS/平台账号、后台角色）
- `iam_menu` = **后台菜单/导航模型**（路由、图标、i18n、排序；靠 `required_permission` 列**引用**权限，自身不定义权限）

`iam_` 这个前缀是**错的**：它把"后台控制台"的东西挂到了"访问控制"域上，于是造成一前缀两域。

**四个选项**（我需要你选，因为这是"后台控制台算不算一个域"的架构判断）：

| 选项 | 做法 | 改名代价 | 评价 |
| --- | --- | --- | --- |
| **A（建议）** | 承认"运营后台控制台"是一个**独立域**，取域词 **`console`** → `console_admin_account`、`console_menu` | 2 张表名；**表名字符串**引用 `saa_admin_account` **14 处** + `iam_menu` **1 处**（另有 SQL 16 / 6 处） | `console` 是**域词**（不是业务线名），自解释；一次修掉 P2 与 P3；也符合"BFF 不拥有领域权威表"——控制台数据本就属控制台 |
| **B** | 菜单归 **访问控制域**：`iam_menu` 保留，但**迁表到 `platform-tenant-service`**（授权权威方） | 0 改名，但需跨服务迁表 + 改 `AdminMenuPo` 位置 | 与 L30 自洽；但菜单是 UI 导航，放进授权域语义仍别扭 |
| **C** | 只改 `iam_menu` → `menu`（2–4 字母域词，作独立"后台菜单"域），`saa_admin_account` 暂登记 `saas` | `iam_menu` 1 处 | 最小改动；但"菜单"是否够格独立成域存疑，且 `saas` 仍是业务线名 |
| **D** | 维持现状，仅在登记表注明 `iam_menu` 为"后台本地读模型" | 0 | 门禁可过，但**一前缀两域的问题被固化**，与你"定义好就行"的目标相悖 |

> 我**不再坚持** v2 的 `saas_` 方案——你说得对，那是把业务线写进表名。**`console_` 才是域词。**

**关于改名代价的准确口径**（重要，避免高估）：实测**表名字符串**的引用数是 `saa_admin_account` **14 处**、`iam_menu` **1 处**（`*.java`），另有 `*.sql` 16 / 6 处。而 PO **类名**（`SaaAdminAccountPo` 在 56 处被引用、`AdminMenuPo` 26 处）**不必跟着改**——`@TableName("…")` 只需改注解里的字符串，类名与仓储名可保持不动。**所以选项 A 的真实代价是改 2 个注解里的字符串 + 迁移 DDL + 索引名，不是 70 处。**

**另注**：`saa_admin_account` 的 DDL 注释本身已写明它与别的域的关系——`idaas_subject`（"集团 IDaaS `group_account.id` SSO 映射键"）、`platform_account_id`（"关联 SaaS 平台账号 `idt_account.id`"）。这说明该表是**运营后台自己的账号**，通过外键式字段**引用**集团与平台账号，**不拥有**它们——进一步印证它该归"运营后台控制台"域，而不是 `iam_`。同理 `StaffController.java` 的注释写明了数据流："identity 服务查/建 EMPLOYEE 账号 → 建 saa_admin_account → tenant 服务分配 `iam_user_role`"。

---

## 七、要你拍板的决策清单

| # | 决策 | 选项 | 建议 |
| --- | --- | --- | --- |
| **D1** | 那 3 张 OAuth 表的域名 | A 保留 `open_` 并写明"开放授权" / **B 改 `oauth_`** / C 并入 `user_` | **B**（7 处引用） |
| **D2** | `iam_menu` + `saa_admin_account` 归属 | **A 独立 `console_` 域** / B 迁入 tenant / C `menu_`+`saas_` / D 维持 | **A**（15 处引用，一次修掉 P2+P3） |
| **D3** | `group`/`idaas` 收敛 | **收敛为 `idaas`** / 两个都登记 | 收敛（各 1 处引用） |
| **D4** | 业务线是否进表名 | **不进**（走目录+库+登记表） / 进（22 前缀全改） | **不进** |
| **D5** | 是否采纳 §三 全部条文（3.1–3.6） | 是 / 修改后采纳 | 是 |
| **D6** | 跨域表（P5）是否显式登记为"跨域共享表" | 是 / 否 | 是 |
| **D7** | 业态是否建域（P7） | **不建，用列** / 建 | **不建** |

**决策后我的执行顺序**：① 改写 `ENGINEERING_RULES.md`（新增 3.1–3.6 条文 + 登记表加"业务线/限界上下文/归属服务"三列 + 新增 `idaas`/`oauth`/`console` 等行）→ ② 同步 `table-prefixes.txt` → ③ 按 D1/D2/D3 执行改名（迁移 + PO + SQL + 索引名）→ ④ 跑 `validate-flyway-conventions.ps1` 确认 7→0 → ⑤ 该门禁转阻断。

> ⚠️ 三处门禁耦合（实测）：`validate-flyway-conventions.ps1` **L167** 要求前缀已登记；**L63-65** 要求已登记前缀必须出现在 `ENGINEERING_RULES.md`（反引号正则，**增列不影响**）；所以登记必须**两处同改**，移除须**先删文档行再删清单行**。
>
> ⚠️ 改名必须在**冻结 tag 之前**完成，否则只能新增 V2 做 RENAME——你已说冻结不急，所以时间窗口是够的。
