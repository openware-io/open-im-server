# SaaS 租户后台菜单与权限分层方案（评审稿 v0.3）

> **所属方案集**：`SAAS_MENU_PERMISSION`（SaaS 导航与权限分层改造）
> **本文件**：`01_ADMIN`，总体方案 + PC 后台菜单/交互（评审稿，先评审后派工）
> **配套示意图**：`SAAS_MENU_PERMISSION_01_ADMIN_mockup.html`（自包含，直接浏览器打开）
> **方案集拆分（已完成，按 `docs/renovation/README.md` 的 `<SERIES>_<NN>_<AREA>` 规范）**：
> `SAAS_MENU_PERMISSION_01_ADMIN.md` —— 本文，总体方案 + PC 后台菜单结构与交互
> `SAAS_MENU_PERMISSION_02_SERVICE.md` —— 权限层次模型、归属元数据 DDL、角色与授予规则、菜单下发契约、数据地基
> `SAAS_MENU_PERMISSION_HANDOFF.md` —— 跨机续接说明（引导语）
>
> 两份方案冲突时**以 `02_SERVICE` 为准**（它按建表语句与聚合 SQL 实测编写）。
> **关联标准**：`docs/business/ACCOUNT_PERMISSION_MODEL.md`、`docs/renovation/SAAS_PLATFORM_01_SERVICE.md` §5.1、`docs/renovation/SAAS_PLATFORM_04_DATA.md`、`docs/renovation/KTV_BUSINESS_01_SERVICE.md` §0.3
> **状态**：待评审（未实施）
> **v0.3 复核说明（2026-09-22）**：本版按 `AdminMenuApplicationService` 、`AdminMenuController`、`AdminMenuItem`、
> 该模块 4 个测试，以及 `gv_saas_admin` 的 `AdminLayout.vue` / `SidebarMenuItem.vue` / `stores/menu.js` /
> `utils/menuPermission.js` / `router/index.js` / `TenantContextSelector.vue` 逐行复核，修正了 v0.2 的 6 处事实错误
> （最重要：**租户菜单是 19 项不是 18 项**；**前端权限映射只有 1 条不是"双份硬编码"**；**前端与上下文都没有
> `businessType`**）。权限与授予侧的修正见 `02_SERVICE` §5.5 / §15。**两者冲突时以 `02_SERVICE` 为准。**

---

## 0. 结论摘要

1. **租户后台的导航必须按两条正交轴组织**：**层级轴**（平台 / 租户 / 组织 / 门店）决定「谁看得见、授权授到哪一层」；**功能域轴**（`core` 通用内核 / `ktv` / `hotel` / `spa` / `massage` / `retail`）决定「这是跨业态能力还是某个业态专属」。
2. **侧边栏改为「租户级区 + 门店级区」两段式**，每段内用一级功能域折叠分组，**最多三层**（功能域 → 模块 → 页面），禁止第四层。
3. **权限码格式保持不变**（`module.resource[.action]`，**2～3 段**——不是"三段"；存量 **44 个**权限码实测、后端 `PermissionGuard` 403 校验、全部 Flyway 种子脚本不动），改为给 `iam_permission` **加归属元数据列**（`scope_level` / `domain_code` / `grantable_levels` / `menu_code`）。这是纯加法，可灰度、可回滚。**`code` 是唯一权威**：`module`/`resource`/`action` 三列**不能反推** `code`（实测 `order.view` 存成 `order`/`order`/`view`），详见 `02_SERVICE` §3.6。
4. **菜单定义从 Java 常量外置到表 `iam_menu`**，菜单可见性从「后端 4 条 path 硬编码 + 前端 1 条 path 映射」改为「`required_permission` + `required_grant` + `business_type` 声明式过滤」。
5. **所有现有路由 `path` 一律不变**，只改分组、顺序、显示层级——避免书签、深链、审计动作码、E2E 用例大面积失效。这条是本方案的硬约束。
6. **业态只影响菜单与数据范围，不参与角色建模**。角色 = 职责（`tenant.owner` / `store.manager` / `store.cashier` / `store.finance`），业态 = 数据过滤维度。否则角色数会按「职责 × 业态」爆炸。
7. **v0.2 修正**：按 138 个 Flyway 迁移实测，「商品管理」是**门店级**（`ord_product.store_id NOT NULL`）、「计价方案」是**门店级**（`tnt_pricing_plan.store_id NOT NULL`）。v0.1 把它们放在租户级/平台级是错的，见 §4。
8. **v0.2 新增 M0 数据地基里程碑**：门店级数据的可切分性（`store_id` 覆盖度）是整套菜单分层能否落地的前提，必须先补，见 §12。

---

## 1. 现状诊断

### 1.1 现状事实（读码结论）

| 项 | 现状 | 位置 |
| --- | --- | --- |
| 菜单下发 | **19 个**租户菜单（不是 18）全部 `parentId=0`、`children=List.of()`，**一层平铺**；`AdminMenuItem` 支持 `children`，**PLATFORM 段的 `iam` 已在用**（2 个子项） | `AdminMenuApplicationService.java:44-71`（19 个 TENANT）、`:38-40`（PLATFORM `iam` + 2 子项） |
| 菜单维度 | `scope` 只有 `PLATFORM` / `TENANT` **两个值**（纯 String，无枚举、无常量），没有 `STORE` / `ORGANIZATION` | `AdminMenuItem.java:9`、`SVC:36-71` |
| 菜单可见性 | 后端 **4 条硬编码 path 判断**（储值/脱敏/运营人员/审计，`SVC:93-96`）；其中**储值那条不是权限码**，是查 `tenant_payment_method(method='WALLET', granted=1)` 表。前端映射**只有 1 条**（`/admin/tenant/currency`），且未登记路径**一律可见** | `filterTenantMenus()` `SVC:85-98`、`isWalletGranted()` `SVC:107-114`、`utils/menuPermission.js:26-43` |
| 菜单元数据 | `AdminMenuItem` 共 8 字段（`id/parentId/code/name/path/icon/scope/children`），**无排序字段、无 i18nKey、无权限码字段**；`code` 存在跨 scope 重名（PLATFORM 与 TENANT 都是 `paymethod`） | `AdminMenuItem.java:9-10`、`SVC:42` 与 `SVC:65` |
| 权限码 | `module.resource.action`，`iam_permission` 表只有 `code/module/resource/action/description/status`，**无层级、无业态归属** | `V1__tnt_iam_baseline.sql` |
| 角色 | `iam_role` 有 `role_type`（sdk/PRESET/TENANT），**无归属层级** | 同上 |
| 授权作用域 | `iam_user_role.scope_type` 已有 `TENANT / ORGANIZATION / STORE / SELF`（**能力已存在**） | 同上 |
| 权限聚合 | `selectPermissionCodes` 已实现**父级授权下沉**：`ur.store_id IS NULL OR ur.store_id = #{storeId}` | `IamSnapshotMapper` |
| 上下文 | `contextId = tenantId:organizationId:storeId`；切门店走 `window.location.reload()` 整页刷新 | `TenantContextSelector.vue` |
| 前端路由 | `meta.scope` 只有 `PLATFORM` / `TENANT` | `router/index.js` |
| 业态 | `tnt_store.business_type` + `tnt_business_type` 枚举表**已存在并可用**，但**菜单不消费** | `V1__tnt_iam_baseline.sql` |
| 组织 | `tnt_organization` 已存在，一期「租户 → 组织 → 门店」一层组织 | `SAAS_PLATFORM_01_SERVICE.md` §5.1 |

### 1.2 已确认的能力（不要推翻，要沿用）

1. **授权作用域已经是四层**：`iam_user_role.scope_type` 的 `TENANT/ORGANIZATION/STORE` 已落地，`selectPermissionCodes` 的 SQL 已按「父级绑定对子级上下文生效」聚合。**本方案不重造授权链，只补元数据与展示层次。**
2. **权限传播链已闭环**：`iam_user_role → iam_role → iam_role_permission → iam_permission` → 权限快照 → 签 `X-Tenant-Context` token → 各服务 `TenantContextFilter` 解析 → `TenantContextHolder`。菜单分层只影响「入口可见性」，越权拦截仍由这条链强制，**菜单不是安全边界**。
3. **业态枚举已可配**：`tnt_business_type`（`KTV/HOTEL/SPA/MASSAGE/RETAIL`，可注册扩展），`tnt_store.business_type` 一个门店一种业态。
4. **分层模型已有现成范本**：`ord_catalog_item` 与 `pay_channel_config` 的列注释明写「`store_id NULL` = 租户级」，读用 COALESCE、写用门店行覆盖。见 §11。

### 1.3 问题清单

| # | 问题 | 后果 |
| --- | --- | --- |
| P1 | 租户后台菜单一层平铺 **19 项** | 侧边栏过长、无法扫读、无信息层级 |
| P2 | 菜单无「租户级 / 门店级」区分 | 币种（租户级）与开台（门店级）并列，用户不知道当前上下文能否操作 |
| P3 | 菜单无业态维度 | A380 同时开 KTV + 酒店 + 按摩时，酒店店长会看到「KTV 配置」「包厢管理」等无关入口 |
| P4 | 权限码无归属元数据 | 授权界面无法按层级分树；无法校验「把门店级权限授到租户级」这类非法授予 |
| P5 | 菜单可见性硬编码在两处，且**方向相反** | 后端 4 条 path 判断（未命中权限 → 隐藏） + 前端 1 条映射（未登记 → **可见**，`menuPermission.js:41`）。每加一个受门禁菜单要改后端 + 前端 + 测试三处；两边口径一旦漂移，表现为"菜单看得见但点进去 403" |
| P6 | 切门店整页 reload | 租户级操作（改币种、配角色）被门店切换打断 |
| P7 | 平台级菜单归属错位 | `计价方案` 现为 `scope=PLATFORM`，但 `tnt_pricing_plan.store_id NOT NULL`，实际是**门店级** |
| P8 | 门店级事实表 `store_id` 覆盖不全 | 订单明细 / KTV 会话 / 支付退款只能靠 JOIN 推断门店，门店级权限收敛不下去（§12 缺口 2） |

---

## 2. 总体模型：两条正交轴

```
                       功能域轴 (domain_code) ─────────────────────────►
                       core            ktv        hotel     spa/massage   retail
                  ┌──────────────┬────────────┬──────────┬────────────┬──────────┐
   PLATFORM  0    │ 平台运营     │            │          │            │          │
                  ├──────────────┼────────────┼──────────┼────────────┼──────────┤
层级轴  TENANT  1  │ 租户设置     │ 业态默认    │          │            │          │
(scope_level)     │ 会员/资金    │ 模板/配置   │          │            │          │
                  ├──────────────┼────────────┼──────────┼────────────┼──────────┤
      ORGANIZATION 2│ 组织报表    │            │          │            │          │
                  ├──────────────┼────────────┼──────────┼────────────┼──────────┤
        STORE   3  │ 订单/收银    │ 包厢/开台   │ 房态/入住 │ 技师/房间   │ 收银/商品 │
                  │ 库存/交班    │ 服务人员    │ 房价     │ 计时服务    │ 条码      │
                  │ 计价方案     │             │          │            │          │
                  └──────────────┴────────────┴──────────┴────────────┴──────────┘
```

**每个菜单节点、每个权限码，都必须同时声明 `scope_level` 和 `domain_code`。** 这是本方案的核心约束。

### 2.1 层级轴（scope_level）

| 值 | 含义 | 数据隔离键 | 典型功能 |
| --- | --- | --- | --- |
| `PLATFORM` | 平台运营，跨租户 | 无（`tenantId=0` 允许） | 租户开通、平台 IAM、支付渠道授权 |
| `TENANT` | 租户（= 集团/法人主体） | `tenant_id` | 币种、会员规则、积分规则、支付渠道默认值、通用目录、运营人员、角色权限、审计 |
| `ORGANIZATION` | 组织（品牌/事业部/区域），一期一层 | `organization_id` | 跨店汇总报表、组织级策略下发（**一期只做展示位，不做独立页面**） |
| `STORE` | 门店，**数据隔离最细粒度** | `store_id` | 开台、订单、收银、交班、预约、资源/房态、库存、门店商品、计价方案、门店日报、门店配置 |

**归属判定规则（唯一口径，评审时以此为准）：**

> 看该功能的**权威数据主键里有没有 `store_id`**。
> - 有 `store_id` → 门店级（`STORE`）。同一功能在 N 家门店就是 N 份数据。
> - 只有 `tenant_id`（全租户唯一一份） → 租户级（`TENANT`）。
> - 只有 `organization_id` → 组织级（`ORGANIZATION`）。
> - 无租户约束 → 平台级（`PLATFORM`）。

**「同一店铺可能开多家」不改变菜单结构**：门店级菜单对每家门店都一样，只是数据不同；跨店能力（多店对比、跨店汇总）放租户级，例如「经营总览」。

### 2.2 功能域轴（domain_code）

| 值 | 含义 | 与 `tnt_business_type` 关系 |
| --- | --- | --- |
| `core` | **跨业态统一内核**：订单、资金、会员/积分/储值、营销、库存、资源、报表、租户/组织/门店/IAM | 不对应任何业态，**所有门店都渲染** |
| `ktv` | KTV 业态专属：包厢、服务人员、计时计费、KTV 配置 | `business_type = 'KTV'` |
| `hotel` | 酒店业态专属：房型、房态、房价、入住 | `business_type = 'HOTEL'` |
| `spa` / `massage` | 足浴 / 按摩专属：技师、房间、计时服务 | 同名业态 |
| `retail` | 超市/零售专属：条码、称重、收银台 | `business_type = 'RETAIL'` |

**渲染规则**：`domain_code = core` 的节点永远渲染；`domain_code != core` 的节点**仅当当前上下文门店的 `business_type` 命中时渲染**。

**命名约定**：`core` 权限沿用现有前缀（`order.*` / `payment.*` / `member.*` / `tenant.*` / `iam.*` / `resource.*`）；业态权限必须带业态前缀（`ktv.*` / `hotel.*` / `retail.*`）。

**⚠️ 口径澄清：`resource.*` 归 `core` 不归业态。** `res_resource.resource_type` 已经是业态载体（`KTV_ROOM` / `KTV_SERVER`，后续 `HOTEL_ROOM` / `SPA_ROOM` / `THERAPIST`），一张表 + 一个菜单服务所有业态，业态差异是**数据**不是**分支**。这正是应该推广的模式。

---

## 3. 租户后台菜单结构（提案）

### 3.1 侧边栏两段式线框

```
┌──────────────────────────────┐
│  SaaS 管理后台               │
├──────────────────────────────┤
│  【租户】A380集团        ▾   │  ← 租户上下文标识
│                              │
│  ▸ 经营总览                  │  租户级区（TENANT）
│  ▸ 组织与门店                │  跨店视角，数据按 tenant_id 隔离
│  ▸ 通用目录                  │
│  ▸ 会员与营销                │
│  ▸ 资金与支付                │
│  ▸ 人员与权限                │
│  ▸ 租户设置                  │
│                              │
│ ──────────────────────────── │  ← 分隔线：层级切换的可视边界
│  【门店】A380 KTV旗舰店  ▾   │  ← 门店切换器 + 业态徽标
│         KTV · Asia/Shanghai  │
│                              │
│  ▾ 门店运营                  │  门店级区（STORE）
│      · 包厢看板  (ktv)       │  仅当前门店，数据按 store_id 隔离
│      · 订单                  │
│      · 预约管理              │
│      · 收银/支付             │
│      · 交班/日结             │
│  ▾ 门店资源                  │
│      · 资源管理 (resource_type) │
│      · 服务人员  (ktv)       │
│  ▾ 门店商品与库存            │
│      · 商品管理              │
│      · 商品分类              │
│      · 仓库管理              │
│  ▸ 门店报表                  │
│  ▾ 门店设置                  │
│      · 门店信息              │
│      · 计价方案              │
│      · KTV 配置  (ktv)       │
└──────────────────────────────┘
```

**要点：**

- **两段之间的分隔线是硬边界**，不是普通分组。它回答「我现在的操作会落到整个租户还是这一家店」。
- 门店级区头部**必须显示业态徽标 + 时区**（`tnt_store.business_type` / `timezone`），因为同一租户下不同门店可能业态不同。
- 门店级区在**租户级上下文（无 storeId）时整段不渲染**，并给出「请先选择门店」的空态入口。
- 未命中 `business_type` 的业态分组**整组不渲染**。

### 3.2 租户级功能域明细

| 域 code | 名称 | 子项 | 归属依据 |
| --- | --- | --- | --- |
| `tenant.overview` | 经营总览 | 跨店经营看板（后续）、租户报表（后续） | 跨 `store_id` 聚合，主键无 `store_id` |
| `tenant.org` | 组织与门店 | **门店管理** `/admin/tenant/stores`、组织管理（后续）、法人主体 | `tnt_store` / `tnt_organization` / `tnt_legal_entity` 的父级管理 |
| `tenant.catalog` | 通用目录 | **通用商品/服务目录**（`ord_catalog_item`，`store_id NULL = 租户级通用`）、业态模板（后续） | 目录行 `store_id` 可空，一次定义多店复用 |
| `tenant.crm` | 会员与营销 | **会员管理** `/business/members`、**积分管理** `/business/points`、**储值管理** `/business/wallet`、优惠券/营销（后续） | `cst_*` / `mkt_*` 只有 `tenant_id`；储值挂 `legal_entity_id` |
| `tenant.finance` | 资金与支付 | **支付渠道配置（租户级默认）** `/business/payment-methods`、**币种** `/admin/tenant/currency`、税率/发票（后续） | `pay_channel_config.store_id NULL = 租户级`；币种为租户级唯一来源 |
| `tenant.iam` | 人员与权限 | **运营人员** `/admin/staff`、角色权限（后续，租户自助）、**脱敏权限** `/admin/security` | `iam_*` 按 `tenant_id` |
| `tenant.settings` | 租户设置 | **审计日志** `/admin/audits`、租户信息/接入配置（后续） | `iam_audit_log` / `tnt_tenant_config` |

> **v0.1 → v0.2 变更**：原「商品与服务」域更名为「通用目录」，移出「商品管理」（实为门店级），不再列「计价方案」（实为门店级）。

### 3.3 门店级功能域明细

| 域 code | 名称 | 子项 | domain | 归属依据 |
| --- | --- | --- | --- | --- |
| `store.ops` | 门店运营 | 业态工作台（KTV=**包厢看板**，后续 hotel=房态看板）、**收银台** `/business/orders`、**订单管理** `/admin/orders`、**预约管理** `/business/reservations`、**收银/支付** `/business/payments`、**交班/日结** `/business/shifts` | `core`（工作台按业态切换） | `ord_order` / `ord_reservation` / `pay_shift` / `pay_daily_closing` 均 `store_id NOT NULL` |
| `store.resource` | 门店资源 | **资源管理** `/admin/resources`（`resource_type` 区分 KTV 包厢/酒店房间/技师）、**服务人员**（`ktv`）、房型（`hotel`） | `core` + 业态子项 | `res_resource` / `res_room_type` / `res_occupation` / `res_schedule` 均 `store_id NOT NULL` |
| `store.stock` | 门店商品与库存 | **商品管理** `/admin/products`、商品分类、**仓库管理** `/admin/inventory`、库存流水 | `core` | `ord_product` / `ord_product_category` / `ord_inventory_*` 均 `store_id NOT NULL` |
| `store.report` | 门店报表 | **报表** `/admin/reports`、库存成本毛利、员工业绩、资源利用率 | `core` | 报表按门店维度聚合 |
| `store.settings` | 门店设置 | 门店信息、**计价方案** `/admin/pricing-plans`、支付渠道启用 | `core` + `ktv` | `tnt_pricing_plan.store_id NOT NULL`；`pay_channel_config` 门店行覆盖租户默认 |

> `门店设置` 是**混合域分组**（同时挂 `core` 和业态子项）。规则是「父节点的 `domain_code` 取 `core`，业态子项各自声明业态」——父节点不因业态过滤而消失，只剪掉不匹配的子项。

### 3.4 现有菜单 → 新归属 映射表（迁移用）

**路径一律不变。**

| 现有菜单 | path | 现 scope | 新 scope_level | 新 domain | 新归属 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| 门店 | `/admin/tenant/stores` | TENANT | TENANT | core | 组织与门店 | 不变 |
| 币种 | `/admin/tenant/currency` | TENANT | TENANT | core | 资金与支付 | 门禁保留 `tenant.currency.manage` |
| 包厢/资源 | `/admin/resources` | TENANT | **STORE** | **core** | 门店资源 | 需 `storeId`；业态由 `resource_type` 决定 |
| 预约管理 | `/business/reservations` | TENANT | **STORE** | core | 门店运营 | 需 `storeId` |
| 订单/KTV | `/business/orders` | TENANT | **STORE** | core | 门店运营 | 显示名建议改为 `收银台`（业态由上下文决定；`AdminMenuApplicationService` 现名已是"收银台"） |
| **订单管理** | `/admin/orders` | TENANT | **STORE** | core | 门店运营 | **v0.3 新增行**：菜单 `id=28`/`code=order-manage`，由提交 `a057d771`（2026-09-19）加入，声明位置**紧邻收银台**（`AdminMenuApplicationServiceTest:109-110` 有相邻断言）；v0.2 的映射表漏了它 |
| 仓库管理 | `/admin/inventory` | TENANT | **STORE** | core | 门店商品与库存 | 需 `storeId` |
| **商品管理** | `/admin/products` | TENANT | **STORE** | core | **门店商品与库存** | **v0.2 修正：`ord_product.store_id NOT NULL`** |
| 收银/支付 | `/business/payments` | TENANT | **STORE** | core | 门店运营 | 需 `storeId` |
| 交班/日结 | `/business/shifts` | TENANT | **STORE** | core | 门店运营 | 需 `storeId` |
| 报表 | `/admin/reports` | TENANT | **STORE** | core | 门店报表 | 租户级「经营总览」后续新增 path |
| 会员管理 | `/business/members` | TENANT | TENANT | core | 会员与营销 | 不变层 |
| 积分管理 | `/business/points` | TENANT | TENANT | core | 会员与营销 | 不变层 |
| 支付方式 | `/business/payment-methods` | TENANT | TENANT | core | 资金与支付 | 租户级默认渠道 |
| 储值管理 | `/business/wallet` | TENANT | TENANT | core | 会员与营销 | 保留 `payment.method.wallet` 门禁 |
| 脱敏权限 | `/admin/security` | TENANT | TENANT | core | 人员与权限 | 保留 `iam.role.manage` 门禁 |
| KTV 配置 | `/admin/ktv/config` | TENANT | **STORE** | **ktv** | 门店设置 | 需 `storeId` + 业态命中 |
| 运营人员 | `/admin/staff` | TENANT | TENANT | core | 人员与权限 | 保留 `iam.role.manage` 门禁 |
| 审计日志 | `/admin/audits` | TENANT | TENANT | core | 租户设置 | 保留 `audit.view` 门禁 |
| **计价方案** | `/admin/pricing-plans` | **PLATFORM** | **STORE** | core | **门店设置** | **v0.2 修正：`tnt_pricing_plan.store_id NOT NULL`；path 不变** |

> **覆盖范围（v0.3 校正）**：本表 = **19 个 TENANT 菜单**（§1.1 实测全量）+ 1 个 PLATFORM 菜单（计价方案，迁到门店级）。
> 其中 `审计日志`（`/admin/audits`）在前端路由里**刻意没有 `meta.scope`**（`router/index.js:171-178` 有注释说明），
> 迁移时不要顺手补，也不要把它挪进平台段。
>
> **`code` 需要重命名（新决策点 S-8，见 `02_SERVICE` §9.1）**：现有 `AdminMenuItem.code` 存在跨 scope 重名
> （PLATFORM 与 TENANT 都是 `paymethod`），而 `iam_menu.code` 是唯一键 —— 落地时统一改成带作用域前缀的语义码。
> 前端只把旧 `code` 当 `v-for` key 的兜底（`AdminLayout.vue:22`），不构成对外承诺；**`path` 仍然全部不变**。

**平台级菜单**（`PLATFORM`）同步分组：`平台运营`（租户管理）+ `平台安全`（角色、权限、支付渠道授权）。优先级低。

---

## 4. 对 v0.1 的两处修正（数据模型实测）

v0.1 凭语义推断归属，v0.2 按建表语句实测，发现两处判断错误：

| # | v0.1 判断 | 实测证据 | v0.2 修正 |
| --- | --- | --- | --- |
| 1 | 「商品管理」放**租户级**「商品与服务」——认为目录全租户共享，门店只做库存覆盖 | `V11__ord_inventory_product.sql`：`ord_product.store_id bigint unsigned NOT NULL`；`ord_product_category` 同样 `NOT NULL` | 商品是**门店级**，移入「门店商品与库存」。租户级只保留 `ord_catalog_item`（`store_id NULL`）作为**通用目录** |
| 2 | 「计价方案」从 `PLATFORM` 迁到 **`TENANT`**——认为它是租户可配的经营策略 | `V2__tnt_pricing_plan.sql`：`tnt_pricing_plan.store_id bigint unsigned NOT NULL` | 计价方案是**门店级**，移入「门店设置」。它现在挂在 `scope=PLATFORM` 的 `/admin/pricing-plans` 下，是**层级和归属双重错位** |

**连带影响：**

- 修正 1 后租户级「商品与服务」域只剩通用目录与业态模板，故**更名为「通用目录」**。
- 修正 2 后，`tnt_pricing_plan` 若要支持「租户默认价 + 门店覆盖价」，需先把 `store_id` 改为可空 —— 见 §12 缺口 1。
- 两处修正均**不改变 path**，符合 §3 的硬约束。

---

## 5. 权限层次设计

### 5.1 设计原则

1. **权限码格式不变**：`module.resource[.action]`（**2～3 段**，不是"三段"）。存量 **44 个**权限码（实测）、`PermissionGuard` 的 403 判定、**17 个**含 `iam_permission` 的迁移脚本、单测断言**全部不动**。
2. **加元数据，不加码段**：层级与业态信息写进 `iam_permission` 的新列，而不是编码进 `code`。改码会造成大面积回归，且历史审计日志里的动作码会失配。
3. **权限层级 = 授权层级**：权限的 `scope_level` 决定它**允许被授予到哪一层**。`scope_level=STORE` 的权限不允许绑在 `scope_type=TENANT` 的 `iam_user_role` 上。
4. **上下级叠加、同级并集**（沿用现有 SQL 语义）：门店上下文的有效权限 = `PLATFORM` ∪ 本租户 `TENANT` ∪ 本组织 `ORGANIZATION` ∪ 本店 `STORE`。
5. **菜单是可见性，不是安全边界**：菜单隐藏不构成授权；越权一律由下游服务按 `TenantContext.permissions` 403。

### 5.2 `iam_permission` 归属元数据

```sql
ALTER TABLE `iam_permission`
  ADD COLUMN `scope_level`      varchar(16) NOT NULL DEFAULT 'STORE'
      COMMENT '权限归属层级 PLATFORM/TENANT/ORGANIZATION/STORE' AFTER `action`,
  ADD COLUMN `domain_code`      varchar(32) NOT NULL DEFAULT 'core'
      COMMENT '功能域/业态 core/ktv/hotel/spa/massage/retail' AFTER `scope_level`,
  ADD COLUMN `grantable_levels` varchar(64) NOT NULL DEFAULT ''
      COMMENT '允许绑定的授权作用域，逗号分隔；空串=未配置（非法），规则见 02_SERVICE §4.2' AFTER `domain_code`,
  ADD COLUMN `menu_code`        varchar(64) NULL
      COMMENT '关联菜单节点 code，用于授权界面按菜单树勾选' AFTER `grantable_levels`,
  ADD KEY `idx_iam_permission_scope_domain` (`scope_level`, `domain_code`);
```

> **v0.3 注**：`grantable_levels` 由 `NULL` 改为 `NOT NULL DEFAULT ''`（空串 = 未配置 = 非法），
> 并在回填后 `ALTER COLUMN ... DROP DEFAULT` 让漏配在插入时就报错。**以 `02_SERVICE` §4.1 / §4.2 为准。**

**存量权限的初始归属（示例，实施时全量过一遍）：**

| 权限码 | scope_level | domain_code | 说明 |
| --- | --- | --- | --- |
| `tenant.tenant.manage` | TENANT | core | 租户信息 |
| `tenant.store.manage` | TENANT | core | 门店管理（门店的**父级**操作） |
| `tenant.currency.manage` | TENANT | core | 币种 |
| `iam.role.manage` | TENANT | core | 角色权限 |
| `member.pii.view` | TENANT | core | 脱敏（**可下放到门店**：`grantable_levels` 显式含 `ORGANIZATION,STORE`） |
| `audit.view` | TENANT | core | 审计（同上，可下放到门店） |
| （规划中）`member.view` / `points.*` / `wallet.*` | TENANT | core | 会员内核。**存量 44 个码里没有这些**，属预期新增 |
| `payment.method.*` | TENANT | core | 支付渠道启停 |
| `order.view` / `order.settle` / `order.void` / `order.hold` / `order.transfer` | STORE | core | 订单 |
| `payment.collect` / `payment.refund.*` | STORE | core | 收银 |
| `resource.manage` / `resource.occupy` | STORE | **core** | 资源是跨业态能力，业态差异在 `resource_type` |
| `reservation.create` / `reservation.cancel` | STORE | core | 预约 |
| `ktv.session.open` / `ktv.session.operate` / `ktv.session.correct_pause` | STORE | **ktv** | KTV 履约 |
| `ktv.server.order` / `ktv.server.end` | STORE | **ktv** | KTV 服务人员 |
| （新增）`hotel.stay.checkin` | STORE | hotel | 后续业态 |
| （新增）`retail.cashier.open` | STORE | retail | 后续业态 |

> **⚠️ v0.3 修正**：上表 v0.2 版把 `member.pii.view` 写成 `grantable_levels='TENANT,ORGANIZATION,STORE'`、其余 STORE 码隐含不含 `PLATFORM` ——
> 这与 `platform.operator` 的现状（**44/44 全量持有**，见 §5.3）冲突，会把全部 44 条现状绑定判成非法授予。
> **`grantable_levels` 的生成规则、44 个码的展开结果与 4 条验收 SQL，一律以 `02_SERVICE` §4.2 / §4.3 为准。**
> 一句话：层级序 `PLATFORM > TENANT > ORGANIZATION > STORE`，`grantable_levels` 是**允许绑定的作用域白名单**，
> **44 个码都显式包含 `PLATFORM`**（父级下沉 + PLATFORM 全域），只有更窄的层需要显式下放。

### 5.3 角色归属

```sql
ALTER TABLE `iam_role`
  ADD COLUMN `scope_level` varchar(16) NOT NULL DEFAULT 'STORE'
      COMMENT '角色可绑定的层级' AFTER `role_type`,
  ADD COLUMN `domain_code` varchar(32) NOT NULL DEFAULT 'core'
      COMMENT 'core=跨业态角色；业态值=仅该业态门店可用' AFTER `scope_level`;
```

**预置角色重排（不新增业态角色）：**

| 角色 code | scope_level | domain | 实测持有 | 说明 |
| --- | --- | --- | --- | --- |
| `platform.operator` | PLATFORM | core | **44 / 44（全部）** | 平台运营。**不是"只持 3 个 TENANT 级码"** —— `V24` 对它做了全量授予（`p.status='ACTIVE'`），详见 `02_SERVICE` §5.5 |
| `tenant.owner` | TENANT | core | 38 | 租户老板：缺 6 个 `payment.method.*`（那是平台授予租户的能力开关，租户自身不持码）；靠父级下沉覆盖所有门店、所有业态 |
| `tenant.ops`（建议新增） | TENANT | core | — | 租户运营：跨店报表/会员/目录，**不含资金与退款审批** |
| `store.manager` | STORE | core | 34 | 店长：缺 6 个 `payment.method.*` + `tenant.tenant.manage` + `tenant.currency.manage` + `iam.role.manage` + `audit.view` |
| `store.cashier` | STORE | core | 15 | 收银员 |
| `store.finance` | STORE | core | 5 | 财务 |

> **实测口径**：按迁移版本顺序回放全部 `iam_role_permission` 写入（V3–V26），不是 V11 一次的快照。完整差集表见 `02_SERVICE` §5.1。

> **决策 D-1：不为业态建角色**（如 `store.ktv.manager`）。理由：角色数会按「职责 × 业态」爆炸，且 A380 从 KTV 扩到酒店时运维要重建一整套角色。业态是数据与菜单的过滤维度，不是职责维度。`store.manager` 绑到 KTV 门店自动获得 KTV 权限，绑到酒店门店自动获得酒店权限——见 §5.4。

### 5.4 业态权限的合入方式（D-2）

`store.manager` 在 KTV 门店要有 `ktv.session.open`，在酒店门店要有 `hotel.stay.checkin`。两种做法：

**方案 a（推荐）：权限聚合时按门店业态过滤 `domain_code`。**
`selectPermissionCodes` 增加一个条件：命中的 `iam_permission.domain_code` 必须是 `core` 或等于该门店的 `business_type`。

```sql
-- 在现有 WHERE 基础上追加（示意；正式 SQL 见 02_SERVICE §7.1，含大小写与 fail-closed 处理）
AND (#{storeId} IS NULL OR p.domain_code = 'core' OR p.domain_code = LOWER(s.business_type))
```

- 优点：角色与业态解耦，新增业态不改角色；一条 SQL 解决。
- 代价：多一次 PK 等值 LEFT JOIN；`PermissionSnapshotProviderTest` 需补业态用例（8 条，见 `02_SERVICE` §7.3）。
- **三个必须注意的点（v0.3 补）**：① `tnt_store.business_type` 是**大写**（`KTV`）、`domain_code` 是**小写**，比较必须 `LOWER()`；② 门店不存在/停用时 `s` 为 NULL → 只留 `core`（**fail-closed**，有意为之）；③ `storeId` 为 NULL 的租户/组织上下文必须放行，否则租户级视图丢权限。
- **两个硬前置（v0.3 补）**：门店业态**目前没有写路径**（§8 缺口 6），且事实表的业态是**客户端传值** —— 详见 `02_SERVICE` §10.3 / §10.5。

**方案 b：`store.manager` 授予全部业态权限，靠菜单和数据范围收敛。**
- 优点：零 SQL 改动。
- 缺点：酒店店长的权限快照里带着 `ktv.*`，违反最小权限；平台侧审计口径难看。

**建议采用 a**，但作为 M4 的可选项——M1–M3 不依赖它。

### 5.5 合法授予校验

| 权限 `scope_level` | 允许绑定的 `iam_user_role.scope_type` |
| --- | --- |
| `PLATFORM` | `PLATFORM` |
| `TENANT` | `PLATFORM`、`TENANT`（+ 显式下放层：`member.pii.view`/`audit.view` 可到 `ORGANIZATION`、`STORE`） |
| `ORGANIZATION` | `PLATFORM`、`TENANT`、`ORGANIZATION` |
| `STORE` | `PLATFORM`、`TENANT`、`ORGANIZATION`、`STORE` |

> **⚠️ v0.3 修正**：v0.2 的矩阵把 `PLATFORM` 只放在第一行，并暗示 "`TENANT` 码不能绑 PLATFORM"。这与现状冲突 ——
> `platform.operator` 以 `scope_type='PLATFORM'` 持有全部 44 个码（含 32 个 `STORE` 级）。
> 判据是 `ur.scope_type ∈ permission.grantable_levels`，层级序 `PLATFORM > TENANT > ORGANIZATION > STORE`，
> **允许向宽（父级下沉）、默认禁止向窄**（除非 `grantable_levels` 显式下放）。
> 完整规则 R1–R6 与选项见 `02_SERVICE` §5.5。

**非法授予拦截点**：① 授权界面按 `grantable_levels` 过滤可选项；② 服务端写接口校验（**`IamController.assignUserRole` / `assignPermissions` / `togglePermission` 三个具体落点，现均无校验**），非法返回 400 `INVALID_GRANT_SCOPE`。详见 `02_SERVICE` §8.3。

### 5.6 多门店授权（A380 开 3 家 KTV）

「同一职责授到 N 家门店」现在需要插 N 行 `iam_user_role`。

**建议：不动表结构，保持「一行一店」，接口层提供批量绑定**（`POST /admin/iam/bindings` 带 `storeIds: []`，服务端展开为 N 行 + 一次 `evict(accountId)`）。

理由：`selectPermissionCodes` / `selectContexts` 都是按行匹配，改成分片表要重写全部 IAM 聚合 SQL 与缓存键，收益不匹配风险。批量接口已经解决运维体验。

---

## 6. 菜单模型与下发契约

### 6.1 菜单表 `iam_menu`

```sql
CREATE TABLE `iam_menu` (
  `id`                  bigint unsigned NOT NULL AUTO_INCREMENT,
  `code`                varchar(64)  NOT NULL COMMENT '菜单码，如 store.ops.order',
  `parent_code`         varchar(64)  NULL     COMMENT '父菜单码，NULL=一级',
  `name`                varchar(128) NOT NULL COMMENT '显示名（回退文案）',
  `i18n_key`            varchar(128) NULL     COMMENT 'i18n key，优先于 name',
  `path`                varchar(255) NULL     COMMENT '叶子路由；分组为空',
  `icon`                varchar(64)  NULL,
  `scope_level`         varchar(16)  NOT NULL COMMENT 'PLATFORM/TENANT/ORGANIZATION/STORE',
  `domain_code`         varchar(32)  NOT NULL DEFAULT 'core' COMMENT 'core/ktv/hotel/spa/massage/retail',
  `sort_no`             int          NOT NULL DEFAULT 0,
  `required_permission` varchar(255) NULL COMMENT '权限码，逗号分隔=任一命中即可见',
  `required_grant`      varchar(64)  NULL COMMENT '平台能力开关，如 payment.method.wallet',
  `badge`               varchar(32)  NULL COMMENT '角标类型 new/beta，可空',
  `status`              varchar(24)  NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_menu_code` (`code`),
  KEY `idx_iam_menu_scope_domain_sort` (`scope_level`, `domain_code`, `sort_no`),
  KEY `idx_iam_menu_parent` (`parent_code`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS 后台菜单定义';
```

**为什么用 `code` 做父子关联而不是 `id`**：种子里可读、可幂等重放、跨环境 ID 不稳定也不影响结构。

**种子脚本**：按 §3.2 / §3.3 全量插入，`ON DUPLICATE KEY UPDATE` 幂等。`name` 与 `i18n_key` 双写，前端优先 `i18n_key`（对齐 `docs/i18n-migration-plan.md`）。

> **⚠️ v0.3 修正（编号与落位）**：v0.2 写的是 `V29__seed_iam_menu.sql` —— Flyway 版本号是**按模块独立**的，
> `V29` 在 `platform-tenant-service` 恰好可用、但在 `platform-order-service` 已被
> `V28__ord_order_idempotency.sql` 占据，**不存在全局连号**。
> 建议 `iam_menu` 落在 **`platform-admin-service`**（菜单是 admin 域自己的数据，剪枝所需权限走已有的
> `TenantIamDomainClient.permissions(...)`），对应 **`V7__iam_menu.sql`**。完整落位表见 `02_SERVICE` §11.5。
>
> **`AdminMenuItem` 需要扩字段**：现有 record 只有 8 个字段（`id/parentId/code/name/path/icon/scope/children`），
> **没有排序字段、没有 `i18nKey`、没有权限/域字段** —— M1 用 Java 硬编码组树时至少要补
> `domainCode` 与顺序（或按 `List.of` 声明顺序保持现状）。

### 6.2 下发接口（v2）

`GET /admin/menus/v2?scope=TENANT`

```json
{
  "scope": "TENANT",
  "context": {
    "tenantId": 100, "organizationId": null, "storeId": 3001,
    "businessType": "KTV", "currencyCode": "USD"
  },
  "levels": [
    {
      "level": "TENANT",
      "name": "租户",
      "contextLabel": "A380集团",
      "menus": [
        {
          "code": "tenant.org", "name": "组织与门店", "icon": "office-building",
          "domainCode": "core", "children": [
            { "code": "tenant.org.store", "name": "门店", "path": "/admin/tenant/stores" }
          ]
        }
      ]
    },
    {
      "level": "STORE",
      "name": "门店",
      "contextLabel": "A380 KTV旗舰店",
      "businessType": "KTV",
      "timezone": "Asia/Shanghai",
      "menus": [
        {
          "code": "store.resource", "name": "门店资源", "icon": "grid", "domainCode": "core",
          "children": [
            { "code": "store.resource.room", "name": "包厢/资源", "path": "/admin/resources" }
          ]
        }
      ]
    }
  ]
}
```

**服务端过滤算法（`AdminMenuApplicationService.menusV2`）：**

```
1. 取上下文 (tenantId, organizationId, storeId, businessType) 与权限集合
2. 查 iam_menu（ACTIVE），按 parent_code 建树
3. 剪枝规则（自底向上，叶子先判，父节点无子则连父一起剪）：
   a. scope_level=STORE 的节点：上下文无 storeId → 剪
   b. scope_level=PLATFORM 的节点：非 SUPER_ADMIN / PLATFORM_ADMIN → 剪
   c. domain_code != 'core' 的节点：businessType 不匹配 → 剪
   d. required_permission 非空：权限集合未命中任一 → 剪
   e. required_grant 非空：租户未获该平台能力 → 剪（沿用 isWalletGranted，泛化为通用校验器）
4. 按 scope_level 落到 levels[]，段内按 sort_no 排序
5. 无 storeId 时 STORE 段返回空数组（前端渲染「请选择门店」空态）
```

> **⚠️ v0.3 补注（步骤 1 的数据来源）**：`businessType` **当前不在上下文里**（`SelectContextResponse` / `ContextItem`
> 都没有该字段，见 `02_SERVICE` §10.4）。两个选择：M2 由 `menusV2` 自己按 `storeId` 查一次 `tnt_store.business_type`；
> 或 M4 统一把它加进上下文响应。**建议后者**（门店段头部还要显示时区，一并解决）。
>
> **另一处必须替换的现状**：`filterTenantMenus`（`SVC:85-98`）是**4 条 path 等值判断且不递归 children**；
> 换成声明式剪枝时要注意它现在的三个前置行为：① 上下文为 null → 剔除全部 TENANT 项（`:86`）；
> ② `scope=PLATFORM` 时根本不过滤（`:80`）；③ 其中"储值管理"那条**不是权限码**，是查
> `tenant_payment_method(method='WALLET', granted=1)`（`isWalletGranted`，`:107-114`，**无缓存，每请求一次查询**）
> —— 对应新模型的 `required_grant`，且**迁移时要顺手把它纳入缓存或启动期预取**。

**兼容策略（灰度）：**
- `GET /admin/menus`（旧平铺）**保留**，行为不变，供回滚与老前端使用。
- `GET /admin/menus/v2` 新增。前端按构建期环境变量 `VITE_MENU_V2=on` 切换，两版并行一个发布周期后再下线旧接口。

### 6.3 前端改动清单

| 文件 | 改动 | v0.3 实测注记 |
| --- | --- | --- |
| `src/router/index.js` | `meta` 增加 `menuCode` / `scopeLevel` / `domain`；保留 `scope`。**path 全部不变** | 现 25 条子路由 / 23 条带 `scope`（PLATFORM 5 + TENANT 18）；`/admin/audits` **刻意无 `meta.scope`**，别补 |
| `src/stores/menu.js` | 消费 v2 的 `levels[]`；新增 `tenantMenus` / `storeMenus` / `currentBusinessType` 计算属性；`firstLeafPath()` 兼容新结构 | 现为 `ref` + action 的扁平结构（无 getter），`menus.value = await getMenus(scope)` **原样存响应**（`:28`），改结构要同步 `AdminLayout.vue:288` |
| `src/layout/AdminLayout.vue` | 侧边栏改两段式渲染（两个 `<section>` + 分隔线 + 段头上下文标识）；门店段无 `storeId` 时渲染空态 | 现为**单个 `el-menu` + 一维 `v-for`**，**没有** `el-menu-item-group` / `el-divider` / section，**也没有任何上下文标识**（上下文只在头部）→ 两段式是**新建**，不是改配置。现有空态只有"菜单加载中/错误"（`:26-28`），"请先选择门店"要新写 |
| `src/layout/components/SidebarMenuItem.vue` | 已支持递归，**基本不动**；增加 `domainCode` 徽标渲染（可选） | 已递归（`:2` `el-sub-menu` / `:13` 叶子；`:7-11` 自引用）；徽标目前**硬编码** `/business/orders` 走 `pendingStore.pendingCount`（`:48-52`）—— 分组后仍按 path 命中，**不需要改**；新增域徽标别与它冲突 |
| `src/utils/menuPermission.js` | **删除路径→权限硬编码映射**（改由后端 `required_permission` 决定）。保留 `filterMenusByPermission` 作为兜底（D-5） | 映射**只有 1 条**（`/admin/tenant/currency`，`:26-29`），不是"双份硬编码"；未登记路径**一律可见**（`:41`）—— 与后端"未命中权限即隐藏"**方向相反**，这才是 D-5 要处理的真实风险。`menuPermission.test.js:22-25` 有"映射全量相等"断言，删映射会打破它 |
| `src/components/TenantContextSelector.vue` | 拆成「租户切换」+「门店切换」两个控件，分别落在两段段头；门店切换优先只刷新门店段（M5，先保持 reload 保正确性） | 确认 `setTimeout(() => window.location.reload(), 300)`（`:57`）；组件仅在 `scope === 'TENANT'` 时挂载（`AdminLayout.vue:59`） |
| `src/stores/context.js` | 暴露 `businessType`（来自 context item / select 响应） | ⚠️ **当前上下文里根本没有这个字段**：`SelectContextResponse` = `(tenantId, organizationId, storeId, accountId, authorizationVersion, permissions, currencyCode)`，`ContextItem` 也没有。**这需要后端先加字段**，见 `02_SERVICE` §10.4 —— v0.2 把它当成了纯前端改动 |

**业态专属页面的注册方式**：路由按域分组动态注册，`store.resource` 组下按 `businessType` 加载不同组件，映射表集中一处（`src/router/domain-components.js`），避免在 `AdminLayout` 里散落 `v-if`。

---

## 7. 通用 vs 门店独立：全库数据模型实测

扫描 `gv_im_server` 全部 20 个模块的 138 个 Flyway 迁移、114 张表，按「表的权威数据是不是每家店一份」分四类。

### 7.1 A · 租户通用（只有 `tenant_id`，跨店共享一份）

| 表 | 说明 |
| --- | --- |
| `cst_member` | 会员档案，跨店一档 |
| `cst_point_account` / `cst_point_ledger` | 积分账户与流水（流水带 `business_type`） |
| `cst_wallet_account` / `cst_wallet_ledger` | 储值账户，**挂 `legal_entity_id`（法人主体）而非门店** |
| `mkt_campaign` / `mkt_coupon` / `mkt_coupon_issuance` / `mkt_coupon_redemption` / `mkt_consent` | 营销全族 |
| `tnt_tenant` / `tnt_organization` / `tnt_legal_entity` / `tnt_tenant_config` / `tnt_business_type` | 租户/组织/主体/配置/业态字典 |
| `iam_role` / `iam_role_permission` / `iam_permission` / `iam_approval` | IAM 元数据 |
| `iam_audit_log` | 审计（**无 `store_id`，见 §12 缺口 3**） |
| `saa_admin_account` / `idt_account` / `idt_login_identity` / `idt_oauth_link` / `idt_profile_sync_record` | 后台账号 / SaaS 平台账号 |
| `support_media_*` | 公共支撑域 |

**这一层新增业态（酒店/按摩/超市）零代码改动。**

### 7.2 B · 门店独立（`store_id NOT NULL`，每家店一份）

| 表 | 关键列 |
| --- | --- |
| `ord_order` | `tenant_id` + `organization_id` + `store_id` + **`business_type`** ← **四层键齐全，标准范本** |
| `ord_reservation` | `store_id` + `business_type` |
| `res_resource` | `store_id` + **`resource_type`**（`KTV_ROOM`/`KTV_SERVER`，后续 `HOTEL_ROOM`/`SPA_ROOM`/`THERAPIST`） |
| `res_room_type` | `store_id`（`unit_price NULL` 回退门店级单价） |
| `res_occupation` / `res_schedule` | `store_id` |
| `ord_inventory_material` / `ord_inventory_stock` / `ord_inventory_transaction` | `store_id` |
| `ord_product` / `ord_product_category` | **`store_id NOT NULL`** ← v0.2 修正依据 |
| `tnt_pricing_plan` | **`store_id NOT NULL`** ← v0.2 修正依据 |
| `pay_shift` / `pay_daily_closing` / `pay_intent` | `store_id` |
| `pay_channel_provider` | `store_id` |

### 7.3 C · 混合：租户定义 + 门店覆盖（`store_id NULL` = 租户默认）★

**这是分层能力的关键，当前只有 3 张表做到。**

| 表 / 列 | 分层方式 | 成熟度 |
| --- | --- | --- |
| `ord_catalog_item` | 列注释明写 **「门店ID，NULL=租户级通用」** | ✅ 完整实现 |
| `pay_channel_config` | 列注释明写 **「NULL=租户级」** | ✅ 完整实现 |
| `res_room_type.unit_price` / `server_unit_price` | 「NULL 回退门店级单价」 | 🟡 只做了价格字段，未做行级分层 |
| `iam_user_role` | `tenant_id` / `organization_id` / `store_id` 三列 + `scope_type` | ✅ 授权继承已完整 |

**分层范本（新需求照抄）：**

```sql
store_id bigint unsigned NULL COMMENT '门店ID，NULL=租户级通用'
-- 读：COALESCE(门店行, 租户行)
-- 写：门店行覆盖租户行
```

### 7.4 D · 派生门店级（无 `store_id`，靠父键间接归属）⚠️ 最大技术债

| 表 | 归属路径 |
| --- | --- |
| `ord_order_item` | → `ord_order.store_id` |
| `ord_ktv_session` / `ord_ktv_server_session` | → `order_id` → `ord_order.store_id` |
| `pay_collect` / `pay_refund` / `pay_transaction` | → `order_id` / `payment_intent_id` |

**后果**：门店级权限、门店级报表、门店级审计若要按 `store_id` 收敛，这些表**无法直接过滤，必须 JOIN 回 `ord_order`**。

**建议**：写时冗余 —— 统一订单的所有子表补 `store_id` + `business_type`（订单创建时一起写入，不可变）。读时直查，不用 JOIN。这个改动越早做越便宜。

### 7.5 E · 平台级（无租户约束）

`tenant_payment_method`（平台授予租户某项支付能力，`AdminMenuApplicationService.isWalletGranted()` 查的就是它——`required_grant` 剪枝规则的现成实现）、`pay_channel_provider`（平台侧）、`saa_admin_account`。

### 7.6 新增功能时的决策树（写进工程规范）

```
① 这张表的权威数据是不是「每家店一份」？
   ├─ 是 ──────────────────────► B 门店独立：store_id NOT NULL，菜单挂门店段
   └─ 否
      └─ ② 是否允许门店覆盖租户默认值？
         ├─ 是 ─────────────────► C 混合：store_id NULL = 租户默认，读用 COALESCE
         └─ 否 ─────────────────► A 租户通用：只有 tenant_id，菜单挂租户段

✗ 绝不允许出现 D —— 归属靠父键推断的表一多，门店级权限就是空谈。
```

### 7.7 业态维度如何承载

| 路线 | 表 | 做法 | 评价 |
| --- | --- | --- | --- |
| **统一表 + 类型枚举** | `res_resource`（`resource_type`）、`tnt_pricing_plan`（同 `resource_type`） | 一张表承载多业态，业态是**数据**不是分支 | ✅ **正确做法，应该推广** |
| **按业态分表** | `ord_ktv_session` / `ord_ktv_server_session` | 表名带业态前缀，酒店需另建 `ord_hotel_stay` | 🟡 履约状态机差异大，分表可接受 |
| **事实行带业态** | `ord_order` / `ord_reservation` / `cst_point_ledger` 的 `business_type` | 事实行自带业态 | ✅ 跨业态汇总靠它 |

**规范建议**：新增业态时，**资源与计价走「枚举泛化」，履约走「分表」**。这条分界目前只在 `SAAS_PLATFORM_01_SERVICE` §5.1 有方向，未明确区分两条路线，建议补进标准。

---

## 8. 缺口与技术债

| # | 缺口 | 严重度 | 影响 | 建议 |
| --- | --- | --- | --- | --- |
| 1 | `tnt_pricing_plan.store_id NOT NULL` | **高** | A380 开 3 家 KTV 要配 3 遍计价方案；且它挂在 `scope=PLATFORM` 的 `/admin/pricing-plans` 下——层级和归属双重错位 | 改 `store_id NULL = 租户默认`；菜单归入门店设置 |
| 2 | 派生子表无 `store_id`（`ord_order_item` / `ord_ktv_session` / `ord_ktv_server_session` / `pay_collect` / `pay_refund` / `pay_transaction`） | **高** | 门店级权限 / 报表 / 审计无法直接收敛，必须 JOIN 回 `ord_order`。**这是「门店级菜单与权限」能否真正落地的最大障碍** | 写时冗余 `store_id + business_type`（订单创建时一起写、不可变）；读时直查 |
| 3 | `iam_audit_log` 无 `store_id` | 中 | 门店级审计只能翻 `detail_json`，无法过滤 / 索引 | 加可空 `store_id` |
| 4 | `mkt_campaign_scope` **「首发骨架不建」** | **高** | 优惠券的「业态及门店范围」在 `SAAS_PLATFORM_01` §营销里**承诺了但未实现**；`rule_snapshot_json` 注释直接写「不可查询」 | 补 `mkt_campaign_scope`（`scope_type` / `business_type` / `store_id`） |
| 5 | `cst_member` 无门店线索 | 低 | 会员跨店通用是**正确商业决策**，但「办卡门店 / 归属门店」这类运营诉求无处落 | 加 `origin_store_id`（可空，仅记录，不参与隔离） |
| 6（v0.3 新增） | **业态双源 + 门店业态无写路径** | **中高** | ① `ord_order.business_type` / `ord_reservation.business_type` 是**客户端传值**（`OrderController.java:326/612`、`ReservationApplicationService.java:1059-1060`），服务端不与 `tnt_store` 交叉校验；② `tnt_store.business_type` **没有任何服务端写路径**（`StoreApplicationService` 只写时区/切点）。D-2 要按门店业态过滤权限，两侧会分叉 | ① 服务端以 `tnt_store.business_type` 为权威做校验/覆盖（`02_SERVICE` §10.5）；② 补业态写路径 + `evictAll()`，或写进运维 SOP（`02_SERVICE` §10.3） |

> **为什么缺口 2 最要紧**：本方案要让「门店级功能放在门店层次」，前提是**门店级数据能被可靠地按 `store_id` 切分**。现在订单明细、KTV 会话、支付退款这几张最核心的事实表都只能靠 JOIN 推断门店——菜单分好了层，权限却收敛不下去，等于只做了一半。

---

## 9. 分期实施路线

| 里程碑 | 内容 | DB 改动 | 可独立上线 | 解决 |
| --- | --- | --- | --- | --- |
| **M0 数据地基**（v0.2 新增） | 子表冗余 `store_id + business_type`（缺口 2）；`tnt_pricing_plan.store_id` 改可空（缺口 1）；补 `mkt_campaign_scope`（缺口 4）；`iam_audit_log` 加 `store_id`（缺口 3） | 改表 + 回填 | ⚠️ 需灰度 | P8、门店级数据可切分性 |
| **M1 菜单树化** | `AdminMenuItem` 用 `children` 组装 §3 的两段式树（暂在 Java 内定义，含 v0.2 修正的商品/计价归属）；前端渲染两段式 + 分隔线 + 业态徽标 | 无 | ✅ 可回滚 | P1、P2、P7 |
| **M2 配置外置** | `iam_menu` 建表 + 种子；`menusV2()` 查表 + 剪枝；保留旧 `/admin/menus` | `iam_menu` | ✅ 双接口并行 | P5 |
| **M3 权限元数据** | `iam_permission` / `iam_role` 加 `scope_level` / `domain_code` / `grantable_levels` / `menu_code`，全量回填；菜单 `required_permission` 化，删除后端 4 条硬编码 if 与前端 `MENU_ITEM_PERMISSIONS` | `iam_permission`、`iam_role` | ✅（回填脚本可重放） | P4、P5 |
| **M4 业态维度** | 上下文下发 `businessType`；业态菜单按门店业态过滤；§5.4 方案 a 的权限聚合按业态收敛；A380 多业态门店实测 | 无（表已存在） | ✅ | P3 |
| **M5 授权界面分层 + 交互优化** | 角色配置页改「租户级权限树 / 门店级权限树」；`grantable_levels` 非法授予拦截；批量多门店绑定接口；门店切换不整页 reload | 无 | ✅ | P4、P6 |

**M1 详细工作包（建议先做，收益最快）：**

1. `AdminMenuApplicationService`：把现有 **19 项**（不是 18）按 §3.4 表格重组为树，`parentId` 与 `children` 正确填充。⚠️ 迁移前先补 `AdminMenuItem` 的 `domainCode`/顺序字段（现 8 字段里没有）。
2. `AdminMenuApplicationServiceTest`：补树形结构断言（对称性、无孤儿父、排序）。⚠️ **现有 2 个用例是位置耦合断言**（`currency` 紧跟 `store`、`order-manage` 紧跟收银台），重组必然打破；唯一现有的树形断言只是 `currency.children().isEmpty()`。
3. `AdminLayout.vue`：两段式渲染，段头上下文标识 —— 现文件**无分组/分隔线/上下文标识**，是新建而非改配置；`SidebarMenuItem.vue` 已支持 `children`，基本不动。
4. `menuPermission.test.js`：路径映射保留（M1 不动权限口径）。注意 `:22-25` 有"映射全量相等"断言。
5. 视觉走查：与基线截图对比，确认 **19 项**收敛为 12 个一级域。
6. **回归基线**：`GET /admin/menus`（旧平铺）响应结构不变 —— `AdminMenuControllerAuthTest` 4 个用例覆盖 401/200 与 `scope` 过滤，M1 不能破坏它。

**M0 与 M1 的依赖关系**：M1 不依赖 M0，可并行；但 **M4（业态维度）与 M5（门店级授权界面）必须以 M0 为前提**，否则门店级权限没有可靠的数据切分基础。

---

## 10. 验收标准

**功能验收**

- [ ] 租户后台侧边栏最多 12 个一级域，**每个域展开不超过 5 个子项**，全展开不超过 3 层。
- [ ] 侧边栏存在明确的「租户级 / 门店级」分隔边界，段头显示当前租户名与门店名。
- [ ] 未选门店（`contextId` 无 `storeId`）时，门店段显示「请先选择门店」空态，不显示门店级叶子。
- [ ] 门店 `business_type=HOTEL` 时，KTV 专属分组（门店资源/服务人员/KTV 配置）**完全不可见**。
- [ ] 门店 `business_type=KTV` 时，KTV 专属分组可见，且仅当上下文带 `storeId`。
- [ ] 「商品管理」「计价方案」出现在**门店级段**，不出现在租户级段（v0.2 修正落点）。
- [ ] 无 `tenant.currency.manage` 的账号看不到「币种」（口径与现状一致）。
- [ ] 未授予 `payment.method.wallet` 的租户看不到「储值管理」。
- [ ] 无 `audit.view` 看不到「审计日志」；无 `iam.role.manage` 看不到「运营人员」「脱敏权限」。
- [ ] **所有现有 `path` 可直达**（深链、书签不失效）。

**工程验收**

- [ ] `GET /admin/menus` 行为与响应结构不变（回归基线）。
- [ ] `GET /admin/menus/v2` 有单测覆盖每个剪枝规则（a–e）与「父节点因无子被剪」。
- [ ] `iam_menu` 种子脚本可重复执行（幂等），二次执行无数据变化。
- [ ] 权限元数据回填脚本：`scope_level='STORE'` 的权限码数量、`domain_code != 'core'` 的数量与 `SELECT` 清单一致。
- [ ] M0 回填后：`ord_order_item` / `ord_ktv_session` / `pay_refund` 等的 `store_id` 非空率 100%，且与 `ord_order.store_id` 一致（一致性校验 SQL 纳入 CI）。
- [ ] 前端 `npm test` 通过；`menuPermission` 相关用例同步更新。
- [ ] 越权验证：直接访问被菜单隐藏的 path，接口仍按 `TenantContext.permissions` 返回 403（菜单不是安全边界）。

---

## 11. 影响面与风险

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 入口位置变化导致用户找不到 | 菜单从平铺改为分组，老用户肌肉记忆失效 | ① **path 全不变**；② 发版附「菜单新位置对照表」；③ 首版保留搜索/最近访问（后续） |
| M0 回填期间数据不一致 | 子表补 `store_id` 需要回填历史数据，回填与新写入并存 | ① 先加可空列 + 双写；② 回填；③ 校验一致后置 `NOT NULL`。三阶段灰度 |
| 双接口并存期数据漂移 | 旧接口与新接口各自发展 | 旧接口冻结、只读、不再加菜单；一个发布周期后删除 |
| 权限元数据回填错位 | 某权限 `scope_level` 判错 → 授权界面授错层 | 回填脚本产出 diff 清单人工过审；`grantable_levels` 默认取「自身及以下」保守值 |
| 业态过滤把入口藏没了 | 业态值大小写/新增业态未登记 | 业态匹配大小写不敏感；`business_type` 不在 `tnt_business_type` 登记时保留 `core` 节点 + 记录 WARN 日志 |
| `iam_menu` 成为第二份权限事实 | 菜单表存 `required_permission`，与 `iam_permission` 可能不一致 | 启动期/CI 校验：`iam_menu.required_permission` 中的每个码必须存在于 `iam_permission`，否则构建失败 |
| 平台运营视角被忽略 | 平台运营切租户上下文后看到租户分层菜单 | §6.2 步骤 b：`PLATFORM` 节点按后台角色过滤；平台运营进租户上下文时额外显示平台专属段（后续，D-6） |

**需要同步的清单**（对齐 `renovation/README.md` 要求）：

- Flyway：**按模块各自编号**（v0.3 修正，原 `V28/V29/V30/V31` 全局连号不成立）——
  `platform-order-service` V29（M0 子表 `store_id`）、`common-payment-service` V11（同上）、`common-audit-service` V5（审计 `store_id`）、
  `platform-marketing-service` V5（`mkt_campaign_scope`）、`platform-admin-service` V7（`iam_menu`）、
  `platform-tenant-service` V28+（权限/角色元数据与回填）。完整落位表见 `02_SERVICE` §11.5
- 服务端：`platform-admin-service`（`AdminMenuApplicationService`、`AdminMenuController`、`AdminMenuItem`、`TenantIamDomainClient`）、`platform-tenant-service`（`IamSnapshotMapper`、`PermissionSnapshotProvider`、`IamController`、`StoreApplicationService`、`ContextDtos`）、`platform-order-service`（子表写入冗余 + 业态权威来源）、`common-payment-service`（同上）、`common-audit-service`（审计 `store_id`）、`platform-marketing-service`（`mkt_campaign_scope`）
- OpenAPI：`GET /admin/menus/v2`、`GET/POST /admin/iam/*`（M5 批量绑定 + `INVALID_GRANT_SCOPE`）、上下文响应新增 `businessType`/`timezone`
- PC 后台：`gv_saas_admin`（router / stores / layout / utils）
- 测试：`AdminMenuApplicationServiceTest`（**现有 2 个位置耦合断言必须同步改写**）、`AdminMenuControllerAuthTest`、`PermissionSnapshotProviderTest`、`IamController` 校验测试、`PermissionSnapshotCacheTest`、`menuPermission.test.js`、`stores/context.test.js`、`utils/context.test.js`
- CI：`02_SERVICE` §4.3 四条 SQL + §9.4 六条断言
- 部署配置：前端 `VITE_MENU_V2` 开关

---

## 12. 待评审决策点

| # | 决策 | 建议 | 影响 |
| --- | --- | --- | --- |
| **D-1** | 是否为业态建角色（`store.ktv.manager`） | **不建**。角色 = 职责，业态 = 数据/菜单过滤 | 角色数量、运维复杂度 |
| **D-2** | 业态权限如何合入（§5.4） | **方案 a**：权限聚合按门店业态过滤 `domain_code` | 需改 `selectPermissionCodes` + 单测 |
| **D-3** | `ORGANIZATION` 层一期是否出现在侧边栏 | **只留模型位，不渲染独立段**。组织仅在门店选择器里作为层级路径展示 | 一期工作量 |
| **D-4** | 租户级上下文（无 `storeId`）下，业态专属菜单如何处理 | **折叠为「业态配置」聚合入口**，不按业态展开（避免 A380 三业态时租户级菜单爆炸） | 租户级菜单长度 |
| **D-5** | 前端是否保留权限兜底过滤（`filterMenusByPermission`） | **保留但清空映射表**。⚠️ v0.3 注：现状是"**未登记路径一律可见**"（`menuPermission.js:41`），与后端"未命中权限即隐藏"**方向相反**，所以"保留"意味着前端比后端宽松 —— 必须先明确要哪一侧保守，再决定是否清空映射 | 前后端口径不一致的方向 |
| **D-6** | 平台运营进入租户上下文时，是否额外渲染「平台专属段」 | 本期**不做**，平台运营仍走 `/select` 切后台入口 | 平台运营体验 |
| **D-7** | ~~`计价方案` 从 `PLATFORM` 迁到 `TENANT`~~ | **v0.2 已修正**：`tnt_pricing_plan.store_id NOT NULL` → 迁到 **`STORE`**；若要支持租户默认价需先改表（缺口 1） | 平台运营授权口径 |
| **D-8** | 门店切换是否在 M5 前保持整页 reload | **保持**。正确性优先于流畅度；M5 再改增量刷新 | 交互体验 |
| **D-9** | 菜单显示名：`订单/KTV` 是否改回 `订单` | **改**。业态由上下文决定，菜单名不应带业态后缀 | 文案 |
| **D-10**（新） | M0 是否在 M1 之前做 | **并行**：M1 不依赖 M0 可先上；但 M4/M5 必须先有 M0 | 排期与灰度成本 |
| **D-11**（新） | `res_resource` 的 `resource_type` 是否升为统一「资源类型注册表」 | **建议升**：在 `tnt_*` 增 `resource_type` 字典（含所属 `business_type`），使新增业态的资源类型不靠改代码 | 业态扩展成本 |
| **D-12**（v0.3 新） | `platform.operator` 维持 **44/44 全量**持有，还是收敛剔除业态码 | **维持全量**（零数据改动、零行为变化），把隐式全量改成"显式登记 + CI 断言"。完整三选项见 `02_SERVICE` §5.5.5（= S-6） | 是否动现有授权数据；平台运营体验 |
| **D-13**（v0.3 新） | 业态的权威来源：客户端传值（现状）还是 `tnt_store.business_type` | **门店配置为权威**：入参与门店不一致时 400 或直接覆盖。否则 D-2「按门店业态过滤权限」与「订单按传值落库」会分叉（= `02_SERVICE` S-9 / §10.5） | 业态维度能否站住 |
| **D-14**（v0.3 新） | 是否允许重命名 `AdminMenuItem.code`（`iam_menu.code` 需要唯一 + 语义化） | **允许**：现有 code 跨 scope 重名（两个 `paymethod`），唯一键强制换名；前端只当 `v-for` key 兜底，无对外承诺（= `02_SERVICE` S-8） | 与"只动分组不动 path"硬约束的边界 |
| **D-15**（v0.3 新） | `businessType` / `timezone` 由谁提供：M2 在 `menusV2` 里自查 `tnt_store`，还是 M4 统一加进上下文响应 | **M4 统一加**（门店段头部也要显示时区，一并解决）；M2 可先自查兜底 | M2/M4 的边界与重复查询 |

---

## 13. 变更记录

| 日期 | 版本 | 变更 |
| --- | --- | --- |
| 2026-09-18 | v0.1（评审稿） | 首版：现状诊断、两轴模型、菜单提案、权限元数据、5 里程碑路线、9 项决策点 |
| 2026-09-18 | v0.2（评审稿） | ① 按 138 个 Flyway 迁移实测修正两处归属：商品管理 → 门店级、计价方案 → **门店级**（v0.1 误判为 TENANT）；② 新增 §7「通用 vs 门店独立」四类分类与决策树；③ 新增 §8 五处缺口与技术债；④ 新增 M0 数据地基里程碑；⑤ 决策点 D-7 修正，新增 D-10 / D-11 |
| 2026-09-22 | **v0.3（评审稿 · 实测复核）** | 按 `AdminMenuApplicationService` / `AdminMenuController` / `AdminMenuItem` / 4 个测试 + `gv_saas_admin` 六个文件逐行复核，修正 6 处事实错误并补 4 项决策点：<br>① **租户菜单 19 项不是 18**（`a057d771` 于 09-19 新增"订单管理"`/admin/orders`），§1.1 / §1.3-P1 / §3.3 / §3.4 / §9 全部同步，映射表补该行；<br>② **前端权限映射只有 1 条**（`/admin/tenant/currency`），且未登记路径"一律可见"与后端"未命中即隐藏"方向相反 ⇒ P5 表述修正；<br>③ **存量权限码 44 个、`module.resource[.action]` 2～3 段**（不是"60+、三段"），且 `module/resource/action` 不可反推 `code`；<br>④ **`platform.operator` 持有 44/44 全量**（不是 3 个 TENANT 级码）⇒ §5.3 加实测列、§5.5 矩阵重画（PLATFORM 出现在每一行）；<br>⑤ **前端与上下文都没有 `businessType`/`timezone`** ⇒ §6.3 标注后端需先加字段；<br>⑥ **`AdminLayout.vue` 无分组/分隔线/上下文标识**（两段式是新建），`menuPermission.test.js:22-25` 与 `AdminMenuApplicationServiceTest` 的 2 个位置耦合断言会被 M1 打破；<br>⑦ §8 新增缺口 6（业态双源 + 门店业态无写路径）；§11 Flyway 清单改为**按模块编号**；新增 D-12～D-15 |
