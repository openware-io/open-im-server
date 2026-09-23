# SAAS_MENU_PERMISSION 跨机续接说明（HANDOFF）

> 这不是方案，是**交接便签**：记录了当前进度、已定结论、待办与踩过的坑。
> 换机器 / 换会话后，先读本文件，再读 `SAAS_MENU_PERMISSION_01_ADMIN.md` 与 `SAAS_MENU_PERMISSION_02_SERVICE.md`。
> 最后更新：2026-09-22（v0.3 实测复核）

---

## 0. 另一台电脑的开场引导语（直接复制粘贴）

在新机器上 `git pull` 之后，开一个新的 DSH 会话，把这整段作为**第一句话**发出去：

```text
接手一个已有方案，先读文档再动手，不要重新论证已经定下的结论。

仓库：gv_im_server，分支 develop/2.0.0-saas-20260826（先 git pull）
任务：SaaS 租户后台的「菜单分层 + 权限分层」方案推进

按顺序读这三份（都在 gv_im_server/docs/renovation/）：
1. SAAS_MENU_PERMISSION_HANDOFF.md   —— 交接便签：进度、已定结论、待决策、下一步、环境坑
   （先看它的 §0.2「v0.3 复核修正了什么」——里面 9 条推翻了更早版本的结论，别用旧结论）
2. SAAS_MENU_PERMISSION_01_ADMIN.md  —— 总体方案 v0.3：两轴模型、菜单结构、页面归属、数据模型实测
3. SAAS_MENU_PERMISSION_02_SERVICE.md —— 服务端规格 v0.2：44 个权限码归属、PLATFORM/platform.operator 处理（§5.5）、DDL、授予规则、下发契约、M0 数据地基、Flyway 按模块编号（§11.5）

读完后先向我复述：
  (a) 当前进度到哪一步；
  (b) 你理解的「两轴模型」是什么（scope_level × domain_code）；
  (c) 你打算从哪条路径开始，为什么。

约束（方案里的硬约束，别改）：
- 所有现有前端路由 path 一律不变，只改分组/顺序/显示层级
- 权限码 module.resource[.action] 格式不变（2～3 段，code 是唯一权威，三列不可反推）
- 菜单不是安全边界，越权仍由后端 TenantContext.permissions 403 强制

未拍板的两个决策先问我：
- D-2 业态权限怎么合入（方案 a：权限聚合按门店 business_type 过滤 domain_code）
- D-10 M0 数据地基是否先于 M1

其余决策点（D-1…D-15、S-1…S-10）都有建议值，别逐个来问，先说你的整体打算。

代码零改动，尚未实施。可选起点：M1 菜单树化（不动 DB，可回滚）。
```

> 说明：这段引导语是**自包含**的。新会话不需要这次对话的原始记录，靠这三份文档就能接上。

---

## 0.1 一句话现状

**方案已完成到 v0.3（`01_ADMIN`）+ v0.2（`02_SERVICE`），代码零改动，尚未实施。** 四个交付物已入库：

| 文件 | 内容 |
| --- | --- |
| `docs/renovation/SAAS_MENU_PERMISSION_HANDOFF.md` | 本文，跨机续接说明 |
| `docs/renovation/SAAS_MENU_PERMISSION_01_ADMIN.md` | 总体方案 v0.3：现状诊断、两轴模型、菜单提案、权限元数据概览、数据模型实测、缺口、分期、**15 项决策点（D-1…D-15）** |
| `docs/renovation/SAAS_MENU_PERMISSION_02_SERVICE.md` | 服务端规格 v0.2：**44 个存量权限码全量归属表（实测）**、**PLATFORM 与 `platform.operator` 的正确处理（§5.5）**、`iam_permission`/`iam_role` DDL 与规则化回填、5 个预置角色实测持有量、授予合法性矩阵、业态过滤 SQL 与 8 条测试用例、菜单下发契约与剪枝、M0 四缺口 DDL 与三阶段灰度、**Flyway 按模块编号表（§11.5）**、**10 项决策点（S-1…S-10）** |
| `docs/renovation/SAAS_MENU_PERMISSION_01_ADMIN_mockup.html` | 示意图（自包含单文件，浏览器直接打开） |

**没有动过任何生产代码**，没有新增 Flyway 脚本，没有改表。

### 0.2 v0.3 复核修正了什么（2026-09-22）

v0.1/v0.2 的部分结论是**读语义推断**出来的，这一轮把主代码与迁移种子逐行核了一遍，改了 9 处事实错误。
**接手时先看这张表，别再用旧结论。**

| # | 旧结论（v0.1/v0.2） | 实测 | 影响 |
| --- | --- | --- | --- |
| 1 | `platform.operator` 持有 **3 个** TENANT 级权限 | **持有全部 44 个**（`V24` 对它做了 `p.status='ACTIVE'` 全量授予 + V25/V26 补授），绑定 `scope_type='PLATFORM'` | ⚠️ **最严重**：原判据会把 44 条现状绑定全判非法，并打断平台超管经营权限 ⇒ 新增 `02_SERVICE` §5.5（R1–R6） |
| 2 | 权限码是 `module.resource.action` **三段** | **2～3 段**；`module`/`resource`/`action` 三列**不可反推** `code`（`order.view` 存成 `order`/`order`/`view`） | 任何"拆三段"的实现都会错 |
| 3 | 租户菜单 **18 项** | **19 项**（`a057d771` 于 09-19 加了"订单管理"`/admin/orders`） | 映射表 / 验收口径 / M1 工作包 |
| 4 | 前端 `MENU_ITEM_PERMISSIONS` 与后端构成"双份硬编码" | 前端**只有 1 条**（`/admin/tenant/currency`）；且**未登记路径一律可见**，与后端"未命中即隐藏"方向相反 | P5 的严重度与 D-5 的建议都要改 |
| 5 | 存量权限码 **60+** | **44**（实测口径见 `02_SERVICE` §3.6） | 归属表分母 |
| 6 | "`StoreApplicationService` 更新门店业态时调用 `evictAll()`" | **该写路径根本不存在**：`StoreApplicationService` 只写时区/切点；`tnt_store.business_type` 无任何服务端写路径 | D-2 的配套从"补一行"升级为缺口（§10.3） |
| 7 | 上下文里有 `businessType` / `timezone` | **都没有**（`SelectContextResponse` / `ContextItem` 均无此字段），前端也没有 | M4 需先做后端改动（§10.4） |
| 8 | Flyway 编号 `V28/V29/V30/V31` 全局连号 | **按模块独立编号**：order 服务 V28 已被 `V28__ord_order_idempotency.sql` 占用 | 脚本落位表（`02_SERVICE` §11.5） |
| 9 | `AdminLayout.vue` 改两段式即可 | 现为**单 `el-menu` + 一维 `v-for`**，无分组/分隔线/上下文标识 ⇒ 两段式是**新建**；另 `AdminMenuApplicationServiceTest` 有 2 个**位置耦合断言**会被 M1 打破 | M1 工作量与测试改动 |

> 除此之外还补了三处**新发现的缺口**：门店业态"双源"（事实表业态来自客户端传值，服务端不校验，
> `02_SERVICE` §10.5）、`AdminMenuItem.code` 跨 scope 重名与 `iam_menu.code` 唯一键冲突（S-8）、
> 菜单/路由 path 一致性 CI（§9.4 第 6 条）。

---

## 1. 换机器怎么接上

### 1.1 代码（必做，能拿到）

```bash
git pull            # 分支 develop/2.0.0-saas-20260826
```

三个交付物随提交 `fc2862f8`（v0.2）入库；v0.3 实测复核在同分支后续提交里，`git pull` 即可拿到。若远端 HEAD 比
`dfad2d55` 新，先看 §0.2 的修正表再动手。

### 1.2 上下文（三选一，按需要）

**方式 A（推荐）：用 §0 的开场引导语**

直接复制本文 **§0** 那段文本，作为新会话的第一句话发出去即可。那段是自包含的：
指定了要读哪三份文档、要求 AI 先复述理解、列了不可改的硬约束、点了两个待拍板决策、
给了可选起点。**不需要**原始对话记录。

**方式 B：整体搬 DSH 会话数据（保留完整对话）**

会话文件很小（**0.76 MB**，zstd 压缩的 JSONL），但 Web 端没有 `--resume` 参数，
只能靠 GUI 的会话列表恢复。要搬的话，从本机拷这几项到另一台机器的同名路径：

```
%USERPROFILE%\.dsh\sessions\--D-projects-cnb--\session-<你的会话id>\session.v3.jsonl.zstd
%USERPROFILE%\.dsh\storages\                        (5.3 MB，含 workspace.json / session_projcache)
%USERPROFILE%\.dsh\settings.yaml                    (模型与预设配置)
%USERPROFILE%\.dsh\.credentials.yaml                (API Key —— 注意这是明文凭据)
%USERPROFILE%\.dsh\profiles\web\                    (若改过 web profile)
```

⚠️ **三个坑：**

1. **工作区目录名是路径派生的**。本机是 `--D-projects-cnb--`，因为工作区路径是 `D:\projects\cnb`。
   另一台机器**路径必须一致**，否则要把会话目录手工改成那台机器对应的 slug，
   否则 GUI 的会话列表里看不到。
2. **别拷 `sessions` 全目录**（790 MB）。只拷需要的那一个 `session-*` 目录。
3. **`.credentials.yaml` 是明文 API Key**。走 U 盘/网盘要留意，或者到那边重新登录配置。

**方式 C：只搬方案，不要对话**

方案文档已经把所有结论和依据写全了（含实测证据与文件行号），
新会话从 §1 现状诊断读起即可，不需要原始对话。

---

## 2. 已定结论（不要再重新论证）

这几条是评审基线，除非有人明确反对，否则按此执行：

1. **两轴模型**：`scope_level`（PLATFORM/TENANT/ORGANIZATION/STORE）× `domain_code`（core/ktv/hotel/spa/massage/retail）。每个菜单节点、每个权限码都必须同时声明。
2. **侧边栏两段式**：「租户级区 + 门店级区」，中间一条硬分隔线，段头显示租户名 / 门店名+业态徽标+时区。最多三层，禁止第四层。⚠️ 业态与时区**当前不在上下文响应里**（`02_SERVICE` §10.4），M4 需先补后端字段。
3. **权限码一个字都不改**。`module.resource[.action]` 格式（**2～3 段**，不是"三段"）、存量 **44 个权限码（实测）**、`PermissionGuard` 403 判定、全部 Flyway 种子脚本不动；改为给 `iam_permission` **加元数据列**（`scope_level` / `domain_code` / `grantable_levels` / `menu_code`）。完整归属表见 `02_SERVICE.md` §3。**`code` 是唯一权威，三列不可反推 `code`**（§3.6）。
4. **菜单定义外置到 `iam_menu` 表**，可见性改声明式（`required_permission` / `required_grant` / `business_type`），取代后端 `filterTenantMenus` 的 **4 条硬编码 path 判断** + 前端那 **1 条** path→权限映射（注意：前端未登记路径是"一律可见"，与后端方向相反）。⚠️ `iam_menu.code` 唯一键与现有跨 scope 重名冲突 ⇒ S-8。
5. **所有现有路由 `path` 一律不变**，只改分组/顺序/显示层级。这是硬约束——否则书签、深链、审计动作码、E2E 用例大面积失效。
6. **不为业态建角色**。角色 = 职责，业态 = 数据/菜单过滤维度。否则角色数按「职责 × 业态」爆炸。
7. **`resource.*` 归 `core` 不归业态**。业态差异由 `res_resource.resource_type`（`KTV_ROOM`/`HOTEL_ROOM`/`THERAPIST`…）承载——一张表 + 一个菜单服务所有业态，这是应该推广的模式。

### 2.1 v0.2 的两处修正（按建表语句实测，推翻了 v0.1 的语义推断）

| 功能 | v0.1 误判 | 实测证据 | v0.2 结论 |
| --- | --- | --- | --- |
| 商品管理 | 租户级 | `ord_product.store_id NOT NULL` | **门店级** → 「门店商品与库存」 |
| 计价方案 | TENANT | `tnt_pricing_plan.store_id NOT NULL` | **门店级** → 「门店设置」 |

连带：租户级「商品与服务」域更名为**「通用目录」**（只剩 `ord_catalog_item`，`store_id NULL`）。

### 2.2 数据模型四类分类（全库 114 张表实测）

- **A 租户通用**（只有 `tenant_id`）：`cst_*` 会员/积分/储值、`mkt_*` 营销、`tnt_*` 租户/组织/主体、`iam_*`。**新增业态零改动。**
- **B 门店独立**（`store_id NOT NULL`）：`ord_order`（四层键齐全，标准范本）、`ord_reservation`、`res_*`、`ord_inventory_*`、`ord_product*`、`tnt_pricing_plan`、`pay_shift`/`pay_daily_closing`/`pay_intent`、`pay_channel_provider`。
- **C 混合**（`store_id NULL` = 租户默认）★ **只有 3 张表做到**：`ord_catalog_item`、`pay_channel_config`、`res_room_type` 价格字段；外加 `iam_user_role` 的三层作用域。**这是分层能力的现成范本，新需求照抄。**
- **D 派生门店级** ⚠️ 最大技术债：`ord_order_item`、`ord_ktv_session`、`ord_ktv_server_session`、`pay_collect`、`pay_refund`、`pay_transaction` —— 都没有 `store_id`，只能靠 `order_id` JOIN 推断门店。

### 2.3 权限码归属速查（44 个，实测）

| scope_level | domain | 数量 | 代表码 |
| --- | --- | --- | --- |
| `TENANT` | core | 12 | `tenant.currency.manage`、`iam.role.manage`、`audit.view`、`member.pii.view`、`payment.method.*`（6 个） |
| `STORE` | core | 27 | `order.*`（6）、`reservation.*`（5）、`inventory.*`（7）、`product.*`（4）、`resource.*`（2）、`payment.collect`、`payment.refund.*`（2） |
| `STORE` | **ktv** | 5 | `ktv.session.open`/`operate`/`correct_pause`、`ktv.server.order`/`end` |
| **合计** | | **44** | |

**一个已经踩到的坑（v0.3 重大修正）**：`platform.operator` 是 **PLATFORM 作用域**的角色，
实测持有 **全部 44 个**权限码（`V24__seed_platform_operator_business_permissions.sql` 对 `p.status='ACTIVE'` 全量授予
+ V25/V26 补授），其中 **32 个是 `STORE` 级、5 个是 `ktv` 业态码**。
所以判据是 `iam_user_role.scope_type ∈ permission.grantable_levels`，**不能**说成「`scope_level` 决定能授到哪一层」；
且 `PLATFORM` 作为最宽作用域**出现在矩阵的每一行**（父级下沉）。
完整规则 R1–R6、修正矩阵与三个处理选项见 `02_SERVICE.md` §5.5。

**预置角色 5 个（实测）**：`platform.operator`(平台运营)、`tenant.owner`(租户老板)、`store.manager`(店长)、`store.cashier`(收银员)、`store.finance`(财务)。

---

## 3. 待决策（25 项，最要紧的两项）

完整表在 `01_ADMIN.md` §12（菜单/交互侧，D-1…D-15）与 `02_SERVICE.md` §14（服务端侧，S-1…S-10）。**开工前必须先拍这两个：**

| # | 决策 | 建议 | 卡住什么 |
| --- | --- | --- | --- |
| **D-2** | 业态权限怎么合入 | **方案 a**：`selectPermissionCodes` 按门店 `business_type` 过滤 `domain_code`（正式 SQL 见 `02_SERVICE` §7.1；含大小写、fail-closed、`storeId IS NULL` 放行四个细节） | M4 能不能做干净 |
| **D-10** | M0 是否先于 M1 | **并行**：M1 不依赖 M0；但 M4/M5 **必须先有 M0** | 排期与灰度成本 |

其余 13 项（D-1/3/4/5/6/7/8/9/11/12/13/14/15）都有建议值，不阻塞开工。

**服务端 10 项**（`02_SERVICE.md` §14）：

| # | 决策 | 建议 |
| --- | --- | --- |
| **S-1** | `tnt_pricing_plan.store_id` 用 `NULL` 还是 `0` 表示「租户级默认」 | **用 `0`** —— 采纳后**这个缺口的 DDL 直接消失**，成本转到读写逻辑（§11.1-B） |
| **S-2** | 新增权限码的 `scope_level` 是否强制显式声明 | **强制显式** + 回填后 `ALTER COLUMN ... DROP DEFAULT`，strict 模式下漏配即插入失败 |
| **S-3** | 子表 `store_id` 回填是否需要停机窗口 | **不需要**，三阶段灰度可在线 |
| **S-4** | `grantable_levels` 空值语义 | **空串 = 未配置 = 非法**（不是"不限制"，也不是靠推断"自身及以下"） |
| **S-5** | `res_resource.resource_type` 是否升为字典表 | **建议升**（对应 D-11） |
| **S-6**（新） | `platform.operator` 维持 44 全量，还是收敛剔除业态码 | **维持全量**（零数据改动、零行为变化），把隐式全量改成"显式登记 + CI 断言"（§5.5.5） |
| **S-7**（新） | 平台运营在门店上下文里是否可见/可用业态履约菜单 | 与 S-6 配套；选收敛则靠 `required_permission` 未命中**自动剪掉**，无需额外代码 |
| **S-8**（新） | 是否允许重命名 `AdminMenuItem.code` | **允许**：现有 code 跨 scope 重名（两个 `paymethod`），`iam_menu.code` 唯一键强制换名；前端只当 `v-for` key 兜底 |
| **S-9**（新） | 业态的权威来源：客户端传值还是 `tnt_store.business_type` | **门店配置为权威**，入参不一致时 400 或覆盖（否则 D-2 会分叉，§10.5） |
| **S-10**（新） | 业态变更的落地方式 | **新增门店配置写路径 + 同请求 `evictAll()`**（§10.3 方案 A），并进审计 |

---

## 4. 下一步可选路径

### 路径 1：进 M1（推荐先做，收益最快）

**动什么**：`AdminMenuApplicationService`（把 **19 项**按方案 §3.4 重组成树）、`AdminMenuItem`（补 `domainCode`/顺序字段）、`AdminLayout.vue`（两段式渲染 + 分隔线 + 段头，**现文件无分组能力，是新建**）、`SidebarMenuItem.vue`（基本不动）。
**不动**：DB、权限码、前端路由 path。
**验收**：与基线截图对比，**19 项**收敛为 12 个一级域；所有现有 path 可直达；`GET /admin/menus` 旧响应结构不变（`AdminMenuControllerAuthTest` 4 个用例仍绿）。
**可回滚**：纯前端 + 菜单结构，无 DB 改动。
⚠️ **两个会被打破的现有测试**：`AdminMenuApplicationServiceTest` 的 2 个**位置耦合断言**（`currency` 紧跟 `store`、`order-manage` 紧跟收银台），以及 `menuPermission.test.js:22-25` 的"映射全量相等"，M1 需同步改写。

入口文件：
- `gv_im_server/platform-services/admin/platform-admin-service/src/main/java/com/gvchat/platform/admin/application/AdminMenuApplicationService.java`
- `gv_im_server/platform-services/admin/platform-admin-api/src/main/java/com/gvchat/platform/admin/api/menu/AdminMenuItem.java`
- `gv_saas_admin/src/layout/AdminLayout.vue`
- `gv_saas_admin/src/stores/menu.js`
- 测试：`AdminMenuApplicationServiceTest`、`src/utils/menuPermission.test.js`

### 路径 2：做 M0 数据地基

M0 的**设计与 DDL 已经写在 `02_SERVICE.md` §11**（建表/改列语句、双写、回填、三阶段灰度、一致性校验 SQL），
不需要另起一份方案；直接按 §11 出 Flyway 脚本即可。⚠️ **脚本落位与编号见 §11.5**：Flyway 版本号是**按模块独立**的
（`platform-order-service` 的 V28 已被占用，下一个是 V29），**不存在全局连号**。
若采纳 S-1 的 `store_id = 0` 方案，缺口 1 的 DDL 直接消失（§11.1-B）。

若要独立排期且需要**逐表迁移脚本 + 分批回填参数 + 回滚演练记录**，再新建 `SAAS_MENU_PERMISSION_03_DATA.md`。

对象：`tnt_pricing_plan`（缺口 1）、`ord_order_item`/`ord_ktv_session`/`ord_ktv_server_session`/`pay_collect`/`pay_refund`/`pay_transaction`（缺口 2）、`iam_audit_log`（缺口 3）、`mkt_campaign_scope`（缺口 4，注释里写着「首发骨架不建」，实际未实现）。

⚠️ **M0 与 M1 可并行**；但 **M4 / M5 必须先有 M0**（见 §3 的 D-10）。

### 路径 3：继续评审

把 25 项决策点过一遍（`01_ADMIN.md` §12 的 D-1…D-15 + `02_SERVICE.md` §14 的 S-1…S-10），更新两份方案的变更记录。

---

## 5. 环境与工具注意事项（本机踩过的坑）

1. **本机 `pwsh` 实际是 PowerShell 5.1**（不是 7）。后果：
   - `Set-Content -Encoding utf8` / `Out-File -Encoding utf8` **会写 BOM**。写提交信息用文件时，必须用
     `[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`，
     否则提交信息开头会多一个 `﻿`（我第一次提交就中招，已 amend 修掉）。
   - `>` 重定向默认写 **UTF-16 LE**。验证文件编码**不能**靠重定向后看字节，会得到 `255,254` 的假象。
   - 读 UTF-8 文件要显式 `-Encoding utf8`，否则默认按 GBK 解出乱码（曾据此误判 HTML 标签不闭合）。

2. **写文件工具对"被外部删掉的路径"会陷入死锁**：先报「file changed since it was read」，再报「file no longer exists」，反复重试无效。解法：对同一路径做一次 `read`（失败也行）清掉缓存，或换个文件名。

3. **该仓库有并发开发进程**。曾发生：新建的未跟踪文件在几分钟内被外部 `git clean` 清掉（当时 `git status` 干净、HEAD 是新提交）。
   **对策：产出后尽快 `git add` + commit**，别把重要新文件长时间留在未跟踪状态。

4. **别用 `git add -A`**。并发进程有自己的在途改动，只 `git add` 明确的路径。

5. `gv_saas_admin` 是 Vue 3 + Element Plus + Vite；`npm test`（vitest）与 `npm run build` 是既有门禁。

6. **⚠️ 本文的部分"本机"描述来自另一台机器（v0.3 复核时发现）**。当前这台机器上：
   - `D:\projects\cnb\.agents\notes\stash-archive\` **不存在**（§7.1 的归档没跟过来，见 §7.4）；
   - §7.2 记录的 PID（265760 / 139604 / 146412）**在本机都不存在**；
   - `git reflog` 165 条里**没有任何 stash 记录**，`git reflog show stash` 报 unknown revision
     ⇒ **本机从来没有建过 stash**，那两个补丁只存在于原机器。
   - 本机工作区根下是 **8 个 git 仓库**（不是 9 个），全部 clean。

---

## 6. 相关文件速查

| 用途 | 路径 |
| --- | --- |
| 总体方案（菜单/交互） | `docs/renovation/SAAS_MENU_PERMISSION_01_ADMIN.md` |
| 服务端规格（权限模型/DDL/契约） | `docs/renovation/SAAS_MENU_PERMISSION_02_SERVICE.md` |
| 示意图 | `docs/renovation/SAAS_MENU_PERMISSION_01_ADMIN_mockup.html` |
| 菜单下发（后端） | `platform-services/admin/platform-admin-service/.../application/AdminMenuApplicationService.java` |
| 菜单接口 | `.../platform-admin-service/.../api/controller/AdminMenuController.java`（`GET /admin` + `/menus`；网关外部路径 `GET /api/v1/admin/menus`） |
| 菜单 DTO | `platform-services/admin/platform-admin-api/.../api/menu/AdminMenuItem.java`（8 字段，无排序/i18nKey） |
| 菜单测试 | `.../platform-admin-service/src/test/.../AdminMenuApplicationServiceTest.java`、`AdminMenuControllerAuthTest.java` |
| 权限聚合 SQL | `platform-services/tenant/platform-tenant-service/.../infra/persistence/mapper/IamSnapshotMapper.java`（`selectPermissionCodes` `:62-86`） |
| 权限快照 | `.../platform/tenant/application/PermissionSnapshotProvider.java`（`snapshot/evict/evictAll`） |
| 快照缓存 | `.../platform/tenant/infra/authorization/PermissionSnapshotCache.java`（Redis + 本地，TTL 5 分钟） |
| IAM 写接口（M5 校验落点） | `.../platform/tenant/api/controller/IamController.java`（`assignUserRole` `:166`、`assignPermissions` `:99`、`togglePermission` `:137`） |
| 门店配置写路径（业态缺口） | `.../platform/tenant/application/StoreApplicationService.java`（只写时区/切点） |
| 上下文 DTO（缺 businessType） | `platform-services/admin/platform-admin-api/.../api/context/ContextDtos.java` |
| 侧边栏渲染 | `gv_saas_admin/src/layout/AdminLayout.vue` |
| 侧栏菜单项组件 | `gv_saas_admin/src/layout/components/SidebarMenuItem.vue`（已递归） |
| 菜单 store | `gv_saas_admin/src/stores/menu.js` |
| 前端权限门禁 | `gv_saas_admin/src/utils/menuPermission.js` |
| 路由表 | `gv_saas_admin/src/router/index.js`（25 条子路由，23 条带 `meta.scope`） |
| 上下文切换 | `gv_saas_admin/src/components/TenantContextSelector.vue`、`src/stores/context.js` |
| 账号权限模型（长期约定） | `docs/business/ACCOUNT_PERMISSION_MODEL.md` |
| 多业态总体方案 | `docs/renovation/SAAS_PLATFORM_01_SERVICE.md` §5.1 |
| 核心数据设计 | `docs/renovation/SAAS_PLATFORM_04_DATA.md` |
| 方案命名规范 | `docs/renovation/README.md` |

---

## 7. 工作区清理记录（2026-09-21）

为了让工作区保持干净，本次清理了长期滞留的遗留物。

### 7.1 已清理：2 个 git stash

`gv_im_server` 里有两个无人接续的 stash，**先归档为补丁再 `git stash drop`**：

| 原 stash | 日期 | 内容 | 处置 |
| --- | --- | --- | --- |
| `stash@{0}` | 2026-09-17 | `wip: kind ingress/NodePort 实验`（3 个 k8s 文件） | 归档 → drop |
| `stash@{1}` | 2026-09-05 | `oidc-hybrid cp6 working changes`（openapi 快照 + 导出脚本） | 归档 → drop |

**归档位置**：`D:\projects\cnb\.agents\notes\stash-archive\`（该目录不在任何 git 仓库内，不污染仓库）

```text
stash-archive/
├── README.md                                          ← 每个 stash 的内容、价值判断、恢复方法
├── stash0-20260917-kind-ingress-nodeport.patch        (4.7 KB)
└── stash1-20260905-oidc-hybrid-openapi.patch          (195 KB)
```

**价值判断**：
- `stash0` 是**真实工作**，其中 `MEDIA_INTERNAL_ENDPOINT=http://minio:9000` 那条注释记录了
  「缺该变量会退化为 `http://localhost:9000`，公共媒体对象读取全部 502」的踩坑结论 —— **若要恢复请先确认现在是否已用别的方式解决**。
- `stash1` **已过期**，其 openapi JSON 是 09-05 的生成产物，应用回去会把接口契约倒退；保留仅为留痕。

> 恢复方法见归档目录的 `README.md`（`git apply --stat` 先看，再 `git apply`）。
> 即使补丁丢了，被 drop 的 stash 提交短期内仍在 `git reflog` 里可捞。

> **⚠️ v0.3 复核补充（2026-09-22）**：上面这个归档目录属于**原机器** ——
> 在接手的那台机器上 `D:\projects\cnb\.agents\notes\stash-archive\` **不存在**
> （该目录不在任何 git 仓库内，因此 `git pull` 不会带过来），且本机 `git reflog` 里**没有任何 stash 记录**。
> **结论：那两个补丁目前只在原机器上，无法在本机恢复**；`stash1` 已判定过期、`stash0` 若将来需要
> （`MEDIA_INTERNAL_ENDPOINT` 那条结论）只能回原机器取。
> `stash list` 为空、工作区 clean 这两条在本机**成立**（已复核）。

### 7.4 v0.3 复核时的仓库基线（2026-09-22，当前机器）

- `gv_im_server` 分支 `develop/2.0.0-saas-20260826`，HEAD = `dfad2d55`，工作区**干净**，与 `origin` 偏差 `0 0`
- `git stash list` **为空**，`git reflog` 无 stash 记录
- 工作区根 `D:\projects\cnb` 下 **8 个** git 仓库（`gv_chat_admin` / `gv_chat_app` / `gv_chat_desktop` /
  `gv_chat_turn` / `gv_im_server` / `gv_saas_admin` / `gv_saas_mobile` / `open-website`），全部 clean
- 方案点名的 9 个代码入口文件**全部存在**（`AdminMenuApplicationService.java`、`AdminMenuItem.java`、
  `IamSnapshotMapper.java`、`PermissionSnapshotProvider.java`、`AdminLayout.vue`、`stores/menu.js`、
  `utils/menuPermission.js`、`router/index.js`、`TenantContextSelector.vue`）

### 7.2 未清理（有意保留）：2 个 DSH 子进程

清理时发现 2 个空闲的 `dsh-subprocess-local` 子进程（PID `139604` 起于 09-20 18:07、`146412` 起于 09-20 21:24），
合计约 132 MB，CPU 5 秒增量 ~0.03s（纯计时噪声，确认空闲）。

**没有杀它们**，原因：

1. 它们的父进程是 **DSH web server（PID 265760）**，属于 harness 内部 worker，
   可能仍被某个已加载会话引用 —— 强杀有丢失会话状态的风险，收益只有 132 MB；
2. **顺带提醒：千万不要杀 PID 265760**，那是本机 DSH Web UI 的服务进程，杀了会直接断掉正在进行的会话；
3. 要彻底回收这批进程，正确做法是**重启 DSH web server**（会一并结束所有会话），而不是逐个 kill。

### 7.3 清理时的仓库状态（**原机器**基线，2026-09-21）

- `gv_im_server` 分支 `develop/2.0.0-saas-20260826`，工作区**干净**，`git stash list` **为空**
- 工作区 9 个仓库全部 clean，无未提交改动（v0.3 复核：当前机器是 **8 个**，见 §7.4）
- 并发开发进程最后活动：2026-09-20 21:39（最后一次 ACK dev 发布），此后静默
