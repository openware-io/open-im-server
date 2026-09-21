# SaaS 权限分层与菜单模型方案（服务端 · 评审稿 v0.2）

> **所属方案集**：`SAAS_MENU_PERMISSION`
> **本文件**：`02_SERVICE`，权限层次模型、归属元数据、角色与授予规则、菜单下发契约、数据地基
> **前置**：`SAAS_MENU_PERMISSION_01_ADMIN.md`（总体方案 + PC 后台菜单结构与交互）
> **配套**：`SAAS_MENU_PERMISSION_01_ADMIN_mockup.html`（示意图）、`SAAS_MENU_PERMISSION_HANDOFF.md`（交接）
> **关联标准**：`docs/business/ACCOUNT_PERMISSION_MODEL.md`、`docs/renovation/SAAS_PLATFORM_01_SERVICE.md` §5.1、`docs/renovation/SAAS_PLATFORM_04_DATA.md`
> **状态**：待评审（未实施）
> **v0.2 复核说明（2026-09-22）**：本版按 `platform-tenant-service` 的 27 个迁移种子、
> `IamSnapshotMapper` / `PermissionSnapshotProvider` / `PermissionSnapshotCache` / `IamController` / `StoreApplicationService`
> 主代码逐行复核，修正了 v0.1 的 7 处事实错误。**若与他处（含 `01_ADMIN`）冲突，以本文件为准。**
> 主要修正见 §15 变更记录。

---

## 1. 范围与分工

本文件只写**服务端**：权限归属模型、DDL、授权规则、聚合 SQL、菜单 BFF 与下发契约、数据地基。

| 关注点 | 归谁 |
| --- | --- |
| 侧边栏两段式线框、一级域划分、页面归属、前端改动清单、示意图 | `01_ADMIN.md` |
| 两轴模型定义、权限码归属、角色与授予、菜单表与剪枝算法、迁移与回滚 | **本文** |

`01_ADMIN.md` 是评审视角的总体方案；本文是**实施视角**的服务端规格。两者冲突时以本文为准（本文按建表语句与聚合 SQL 实测编写）。

---

## 2. 权限层次模型

### 2.1 四个层级

| `scope_level` | 含义 | 数据隔离键 | 上下文约束 |
| --- | --- | --- | --- |
| `PLATFORM` | 平台运营，跨租户 | 无（`tenantId=0` 允许） | `scopeType=PLATFORM` |
| `TENANT` | 租户（集团/法人主体） | `tenant_id` | `tenantId > 0` |
| `ORGANIZATION` | 组织（品牌/事业部），一期一层 | `organization_id` | `tenantId > 0` |
| `STORE` | 门店，**数据隔离最细粒度** | `store_id` | `tenantId > 0` 且 `storeId > 0` |

### 2.2 归属判定唯一口径

> 看该权限所操作对象的**权威数据主键里有没有 `store_id`**。
>
> - 有 → `STORE`
> - 只有 `tenant_id` → `TENANT`
> - 只有 `organization_id` → `ORGANIZATION`
> - 无租户约束 → `PLATFORM`

**⚠️ 业态不参与层级判定。** 业态是 `domain_code` 维度，与 `scope_level` 正交。例：`ktv.session.open` 是 `STORE` + `ktv`；`resource.manage` 是 `STORE` + `core`（业态差异由 `res_resource.resource_type` 承载）。

---

## 3. 存量权限码全量归属（44 个码，实测）

从 `iam_permission` 的全部 `INSERT` 种子（V1–V27）提取，实际 44 个 ACTIVE 码。

### 3.1 租户级（6 个）

| 权限码 | module | domain | grantable_levels | 说明 |
| --- | --- | --- | --- | --- |
| `tenant.tenant.manage` | tenant | core | `PLATFORM,TENANT` | 租户信息。**`platform.operator` 也持有**（V11 种子） |
| `tenant.store.manage` | tenant | core | `PLATFORM,TENANT,ORGANIZATION` | 门店管理（门店的父级操作）。**`platform.operator` 也持有** |
| `tenant.currency.manage` | tenant | core | `TENANT` | 币种（租户级唯一来源） |
| `iam.role.manage` | iam | core | `PLATFORM,TENANT` | 角色权限。**`platform.operator` 也持有** |
| `audit.view` | audit | core | `PLATFORM,TENANT,ORGANIZATION,STORE` | 审计。平台看全部租户，租户只看本租户（服务端强制） |
| `member.pii.view` | member | core | `TENANT,ORGANIZATION,STORE` | 脱敏回显明文，**可下放到门店** |

### 3.2 平台授予租户的能力开关（6 个）

| 权限码 | module | domain | grantable_levels | 说明 |
| --- | --- | --- | --- | --- |
| `payment.method.cash` | payment | core | `PLATFORM,TENANT` | 现金 |
| `payment.method.alipay` | payment | core | `PLATFORM,TENANT` | 支付宝 |
| `payment.method.wechat` | payment | core | `PLATFORM,TENANT` | 微信 |
| `payment.method.stripe` | payment | core | `PLATFORM,TENANT` | Stripe |
| `payment.method.point` | payment | core | `PLATFORM,TENANT` | 积分抵扣 |
| `payment.method.wallet` | payment | core | `PLATFORM,TENANT` | 储值（`isWalletGranted()` 判的就是它） |

### 3.3 门店级 · core（27 个）

| 权限码 | 归属域 | 说明 |
| --- | --- | --- |
| `order.view` | 门店运营 | 查看订单与明细 |
| `order.settle` | 门店运营 | 订单结算 |
| `order.hold` | 门店运营 | 挂单/解挂 |
| `order.transfer` | 门店运营 | 转台 |
| `order.void` | 门店运营 | 作废 |
| `order.add_item` | 门店运营 | 加项 |
| `payment.collect` | 门店运营 | 组合收款 |
| `payment.refund.approve` | 门店运营 | 退款审批 |
| `payment.refund.offline` | 门店运营 | 线下退款 |
| `reservation.view` | 门店运营 | 预约查看 |
| `reservation.create` | 门店运营 | 创建预约 |
| `reservation.confirm` | 门店运营 | 确认预约 |
| `reservation.arrival` | 门店运营 | 到店 |
| `reservation.cancel` | 门店运营 | 取消预约 |
| `resource.manage` | 门店资源 | 资源管理（**core**，业态在 `resource_type`） |
| `resource.occupy` | 门店资源 | 资源占用 |
| `product.view` | 门店商品与库存 | 商品查看 |
| `product.manage` | 门店商品与库存 | 商品管理 |
| `product.publish` | 门店商品与库存 | 上架 |
| `product.unpublish` | 门店商品与库存 | 下架 |
| `inventory.material.view` | 门店商品与库存 | 物料查看 |
| `inventory.material.manage` | 门店商品与库存 | 物料管理 |
| `inventory.adjust` | 门店商品与库存 | 库存调整 |
| `inventory.receipt.create` | 门店商品与库存 | 入库 |
| `inventory.recovery.view` | 门店商品与库存 | 回收查看 |
| `inventory.recovery.confirm` | 门店商品与库存 | 回收确认 |
| `inventory.transaction.view` | 门店商品与库存 | 库存流水 |

> `product.*` 归 `STORE` 而非 `TENANT`，依据是 `ord_product.store_id bigint unsigned NOT NULL`（`V11__ord_inventory_product.sql`）—— **这是 v0.2 相对 v0.1 的修正**。

### 3.4 门店级 · ktv 业态（5 个）

| 权限码 | 归属域 | 说明 |
| --- | --- | --- |
| `ktv.session.open` | 门店运营 | 开台/开单 |
| `ktv.session.operate` | 门店运营 | 开台/结台 |
| `ktv.session.correct_pause` | 门店运营 | 暂停修正 |
| `ktv.server.order` | 门店资源 | 服务人员点单 |
| `ktv.server.end` | 门店资源 | 结束服务 |

### 3.5 汇总

| scope_level | core | ktv | 合计 |
| --- | --- | --- | --- |
| `TENANT` | 12 | 0 | 12 |
| `STORE` | 27 | 5 | 32 |
| **合计** | **39** | **5** | **44** |

> **待确认**：`docs/business/ACCOUNT_PERMISSION_MODEL.md` §4.2 提到的 `tenant.tenant.manage` 等码与本文一致；若后续有权限码只存在于代码 `PermissionGuard` 而未入 `iam_permission` 种子，需补一轮核对。核对 SQL 见 §11.4。

### 3.6 权限码形态实测（v0.2 新增，推翻"三段格式"的说法）

原 v0.1 与 `01_ADMIN` §0.3/§5.1 都写「权限码 `module.resource.action` **三段格式不变**」。**实测不是三段**：

| `code` | 段数 | 种子里的 `module` / `resource` / `action` |
| --- | --- | --- |
| `ktv.session.correct_pause` | 3 | `ktv` / `session` / `correct_pause` |
| `order.add_item` | 3 | （同形） |
| `order.view` | **2** | `order` / `order` / `view` ← **`resource` 段被补成了 module 名** |
| `resource.manage` | **2** | `resource` / `resource` / `manage` |
| `product.view` | **2** | `product` / `product` / `view` |
| `reservation.create` | **2** | （同形） |
| `payment.collect` | **2** | （同形） |
| `audit.view` | **2** | （同形） |

实测依据：`V22__seed_ktv_loop_permissions.sql` 的 `('order.view', 'order', 'order', 'view', ...)`、
`('resource.manage', 'resource', 'resource', 'manage', ...)`。

**结论（写进硬约束）**：

1. `code` 是**唯一权威**，形态是 `module.resource[.action]`（**2 或 3 段**）。
2. `module` / `resource` / `action` 三列**不能反推** `code`。想按 `code` 分组、按域展示、
   或在授权树上排列，一律**以 `code` 列为准**（必要时补 `menu_code` 关联，见 §9.1），不要拆 `code` 去猜。
3. 因此本文与 `01_ADMIN` 中所有"三段格式"的措辞统一改为「**`module.resource[.action]` 格式（2～3 段）不变**」。

**44 这个数字的复核口径**（可复现）：扫描 `platform-tenant-service/src/main/resources/db/migration` 下
**17 个含 `iam_permission` 的迁移文件**（V1、V3、V8、V9、V11、V12、V13、V17–V19、V21–V26），
抽取其中形如 `module.resource[.action]` 的字面量去重（排除 5 个预置角色码 `platform.operator` /
`tenant.owner` / `store.manager` / `store.cashier` / `store.finance` 与特性开关 `feature.ktv.enabled`），
得到 **44** 个，与 §3.1–§3.4 的清单逐条一致；`SUM` 交叉校验：12 + 27 + 5 = 44。

---

## 4. 归属元数据 DDL 与回填

### 4.1 `iam_permission` 加列（纯加法，不动 `code`）

```sql
-- V30__iam_permission_scope_metadata.sql
ALTER TABLE `iam_permission`
  ADD COLUMN `scope_level`      varchar(16) NOT NULL DEFAULT 'STORE'
      COMMENT '权限归属层级 PLATFORM/TENANT/ORGANIZATION/STORE' AFTER `action`,
  ADD COLUMN `domain_code`      varchar(32) NOT NULL DEFAULT 'core'
      COMMENT '功能域/业态 core/ktv/hotel/spa/massage/retail' AFTER `scope_level`,
  ADD COLUMN `grantable_levels` varchar(64) NOT NULL DEFAULT ''
      COMMENT '允许绑定的授权作用域，逗号分隔；空串=未配置（非法），生成规则见 §4.2' AFTER `domain_code`,
  ADD COLUMN `menu_code`        varchar(64) NULL
      COMMENT '关联菜单节点 code，用于授权界面按菜单树勾选' AFTER `grantable_levels`,
  ADD KEY `idx_iam_permission_scope_domain` (`scope_level`, `domain_code`);
```

> **⚠️ v0.2 修正 1**：`grantable_levels` 从 `NULL` 改为 **`NOT NULL DEFAULT ''`**。
> `NULL` 与"空串"在断言里都容易漏（`IS NULL` 与 `=''` 要写两遍），统一成空串后 CI 只需一条 `<> ''` 就能全量兜住。
> 语义（S-4 保留）：**空串 = 尚未配置，不是"不限制"**，构建期直接失败。
>
> **v0.2 修正 2（S-2 的落地方式）**：回填完成后追加一条迁移，把两个默认值**摘掉**，让漏配在插入时就报错，
> 而不是悄悄落一个 `STORE`：
>
> ```sql
> -- 回填完成后的下一个迁移版本
> ALTER TABLE `iam_permission`
>   ALTER COLUMN `scope_level`      DROP DEFAULT,
>   ALTER COLUMN `grantable_levels` DROP DEFAULT;
> ```
>
> MySQL 8 支持 `ALTER COLUMN ... DROP DEFAULT`；`NOT NULL` 且无默认值的列在 strict 模式（生产默认）下
> 漏配即插入失败。这比"CI 事后校验"更早拦住问题，CI 断言仍然保留（见 §4.3）作为第二道。

**为什么不动 `code`**：`code` 已被 `PermissionGuard` 的 403 判定、16 个 Flyway 种子脚本、单测断言、历史审计动作码引用。改码会造成大面积回归且审计失配。元数据列是加法，可灰度、可回滚。

> **⚠️ v0.2 修正 3**：`code` **不是**"`module.resource.action` 三段格式"，实测是 **2～3 段**，且
> `module` / `resource` / `action` 三列**无法反推** `code`。实测样例见 §3.6。任何"按三段拆分 code"的实现都是错的，
> 一律以 `code` 列本身为准。

### 4.2 `grantable_levels` 的生成规则（v0.2 重写）

`grantable_levels` 的语义是「**这个权限允许被绑到哪些授权作用域上**」，**不是**「这个权限属于哪一层」。
原 v0.1 把两者混为一谈，直接导致 §5.5 记录的 `platform.operator` 误判。生成规则只有一条：

```
grantable_levels(码) = { PLATFORM } ∪ { 该码 scope_level 及所有更宽的层 } ∪ { 显式声明的下放层 }
```

层级序（左宽右窄）：`PLATFORM > TENANT > ORGANIZATION > STORE`。按 `scope_level` 展开：

| 权限 `scope_level` | 强制包含（父级下沉 + PLATFORM 全域） | 可选下放（必须显式声明） |
| --- | --- | --- |
| `PLATFORM` | `PLATFORM` | — |
| `TENANT` | `PLATFORM,TENANT` | `ORGANIZATION`、`STORE` |
| `ORGANIZATION` | `PLATFORM,TENANT,ORGANIZATION` | `STORE` |
| `STORE` | `PLATFORM,TENANT,ORGANIZATION,STORE` | — |

**44 个码的展开结果**：

| 组 | 码数 | `scope_level` | `domain_code` | `grantable_levels` |
| --- | --- | --- | --- | --- |
| 租户管理类：`tenant.tenant.manage`、`iam.role.manage`、`tenant.currency.manage` | 3 | `TENANT` | `core` | `PLATFORM,TENANT` |
| `tenant.store.manage` | 1 | `TENANT` | `core` | `PLATFORM,TENANT,ORGANIZATION` |
| `payment.method.*` | 6 | `TENANT` | `core` | `PLATFORM,TENANT` |
| `member.pii.view`、`audit.view`（显式下放到门店） | 2 | `TENANT` | `core` | `PLATFORM,TENANT,ORGANIZATION,STORE` |
| §3.3 门店级 core | 27 | `STORE` | `core` | `PLATFORM,TENANT,ORGANIZATION,STORE` |
| §3.4 门店级 ktv | 5 | `STORE` | `ktv` | `PLATFORM,TENANT,ORGANIZATION,STORE` |
| **合计** | **44** | | | |

> **关键点**：44 个码的 `grantable_levels` **全部包含 `PLATFORM`**。这不是给平台运营开后门，
> 而是把「PLATFORM 是不受租户/门店约束的全域作用域」这条语义写进数据 —— 详见 §5.5。

```sql
-- V28__iam_permission_scope_metadata_backfill.sql（platform-tenant-service）
-- ① 门店级 core（§3.3 的 27 个，显式清单，禁止 LIKE 兜底）
UPDATE iam_permission SET scope_level='STORE', domain_code='core',
       grantable_levels='PLATFORM,TENANT,ORGANIZATION,STORE'
 WHERE code IN ('order.view','order.settle','order.hold','order.transfer','order.void','order.add_item',
                'payment.collect','payment.refund.approve','payment.refund.offline',
                'reservation.view','reservation.create','reservation.confirm','reservation.arrival','reservation.cancel',
                'resource.manage','resource.occupy',
                'product.view','product.manage','product.publish','product.unpublish',
                'inventory.material.view','inventory.material.manage','inventory.adjust','inventory.receipt.create',
                'inventory.recovery.view','inventory.recovery.confirm','inventory.transaction.view');

-- ② 门店级 ktv（§3.4 的 5 个）
UPDATE iam_permission SET scope_level='STORE', domain_code='ktv',
       grantable_levels='PLATFORM,TENANT,ORGANIZATION,STORE'
 WHERE code IN ('ktv.session.open','ktv.session.operate','ktv.session.correct_pause',
                'ktv.server.order','ktv.server.end');

-- ③ 租户级（12 个 = 9 + 1 + 2）
UPDATE iam_permission SET scope_level='TENANT', domain_code='core',
       grantable_levels='PLATFORM,TENANT'
 WHERE code IN ('tenant.tenant.manage','iam.role.manage','tenant.currency.manage',
                'payment.method.cash','payment.method.alipay','payment.method.wechat',
                'payment.method.stripe','payment.method.point','payment.method.wallet');
UPDATE iam_permission SET scope_level='TENANT', domain_code='core',
       grantable_levels='PLATFORM,TENANT,ORGANIZATION'
 WHERE code = 'tenant.store.manage';
UPDATE iam_permission SET scope_level='TENANT', domain_code='core',
       grantable_levels='PLATFORM,TENANT,ORGANIZATION,STORE'
 WHERE code IN ('member.pii.view','audit.view');
```

> ⚠️ **三处必须写显式清单**，不要用 `LIKE`/子查询兜底：新增权限码若被兜底语句悄悄归成 `STORE`，
> 层级判错不会有任何报错。原 v0.1 的第三条 UPDATE 就是这种写法，v0.2 已改正。

### 4.3 回填验收 SQL（v0.2 增补）

```sql
-- ① 必须为 0：任何 ACTIVE 码仍是默认值、未配置、或缺少强制项
SELECT code, scope_level, grantable_levels FROM iam_permission
 WHERE status='ACTIVE' AND (
   grantable_levels IS NULL OR grantable_levels = ''
   OR (scope_level IN ('TENANT','ORGANIZATION','STORE') AND FIND_IN_SET('PLATFORM',     grantable_levels)=0)
   OR (scope_level IN ('TENANT','ORGANIZATION','STORE') AND FIND_IN_SET('TENANT',       grantable_levels)=0)
   OR (scope_level IN ('ORGANIZATION','STORE')          AND FIND_IN_SET('ORGANIZATION', grantable_levels)=0)
   OR (scope_level = 'STORE'                            AND FIND_IN_SET('STORE',        grantable_levels)=0)
 );

-- ② 必须为 0：授权数据里存在非法绑定（这是 §8.2 判据的直接校验，也是 S-2 的第二道）
SELECT ur.id, ur.account_id, ur.scope_type, p.code, p.scope_level, p.grantable_levels
  FROM iam_user_role ur
  JOIN iam_role_permission rp ON rp.role_id = ur.role_id
  JOIN iam_permission p ON p.id = rp.permission_id AND p.status = 'ACTIVE'
 WHERE ur.status = 'ACTIVE'
   AND (p.grantable_levels IS NULL OR p.grantable_levels = ''
        OR FIND_IN_SET(ur.scope_type, p.grantable_levels) = 0);

-- ③ 分类计数必须与 §3.5 一致：TENANT=12、STORE=32（其中 ktv=5）
SELECT scope_level, domain_code, COUNT(*) FROM iam_permission
 WHERE status='ACTIVE' GROUP BY scope_level, domain_code;

-- ④ 每个 ACTIVE 码必须至少被一个角色持有（防止"新码上线即 403"，V24 的成因）
SELECT p.code FROM iam_permission p
 WHERE p.status='ACTIVE'
   AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.permission_id = p.id);
```

> ②④ 两条建议同时进 CI 与发布门禁：② 拦"非法授予"，④ 拦"登记遗漏"。
> 实测数据下 ② 的结果应为 0 —— `platform.operator` 的 44 条绑定全部合法，依据见 §5.5。

---

## 5. 角色模型

### 5.1 现有 5 个预置角色（v0.2 重测：全迁移回放，不再是 V11 快照）

原 v0.1 的表只统计了 **V11 一次授予**，而 V3/V8/V9/V12/V13/V17–V19/V21–V26 之后仍在继续授权。
按迁移版本顺序回放全部 `iam_role_permission` 写入后，**当前有效**的持有量如下：

| 角色 code | 名称 | `role_type` | V11 初授 | **当前有效（回放）** |
| --- | --- | --- | --- | --- |
| `platform.operator` | 平台运营 | PRESET | 3 | **44 / 44（全部）** |
| `tenant.owner` | 租户老板 | PRESET | 14 | **38** |
| `store.manager` | 店长 | PRESET | 12 | **34** |
| `store.cashier` | 收银员 | PRESET | 6 | **15** |
| `store.finance` | 财务 | PRESET | 4 | **5** |

**各角色相对全量的差集**（便于核对）：

| 角色 | 不持有的码 | 数量 |
| --- | --- | --- |
| `platform.operator` | 无 | 0 |
| `tenant.owner` | `payment.method.*`（6） | 6 |
| `store.manager` | 上 6 个 + `tenant.tenant.manage`、`tenant.currency.manage`、`iam.role.manage`、`audit.view` | 10 |
| `store.cashier` | 上 10 个 + `ktv.session.correct_pause`、`order.void`、`payment.refund.approve`、`payment.refund.offline`、`member.pii.view` + 全部 `product.*`(4) + 全部 `inventory.*`(7) + `resource.manage` + `reservation.cancel` | 29 |
| `store.finance` | 除 `order.view`、`order.void`、`payment.refund.approve`、`payment.refund.offline`、`member.pii.view` 之外的全部 | 39 |

**回放依据（逐条可查）**：`V24__seed_platform_operator_business_permissions.sql:8-18` 对 `platform.operator`
执行的是 **`JOIN iam_permission p ON p.status = 'ACTIVE'` 的全量授予**（当时一次授出 28 个），
加上 V11 已有的 14 个、V13 `member.pii.view`、V25 `audit.view`、V26 `tenant.currency.manage`，
合计 44。`V24` 的注释写明它的成因：「平台运营进入租户上下文后，仓库等业务接口一律 403 PERMISSION_DENIED」，
且平台超管 `admin` 正是靠这个绑定取得经营权限
（`docs/business/ACCOUNT_PERMISSION_MODEL.md` L80：SUPER_ADMIN 不覆盖 platform.operator）。

> **⚠️ v0.2 重大修正**：`01_ADMIN`/本文 v0.1 写的「`platform.operator` 持有 **3 个** `TENANT` 级权限」
> 是**只看了 V11 的误判**。真实情况是 **44/44 全量持有**，其中 **32 个是 `STORE` 级、5 个是 `ktv` 业态码**，
> 绑定行的 `scope_type` 是 `PLATFORM`。这不是"跨层授予的特例"，而是"PLATFORM 是全域作用域"的常态。
> 原判据（`scope_type ∈ grantable_levels` + STORE 码不含 PLATFORM）会把**全部 44 条现状绑定判成非法**，
> 并连带打断了平台超管的经营权限。完整处理方案见 **§5.5**，这是本文件 v0.2 的主要新增内容。

### 5.2 `iam_role` 加列

```sql
ALTER TABLE `iam_role`
  ADD COLUMN `scope_level` varchar(16) NOT NULL DEFAULT 'STORE'
      COMMENT '角色可绑定的层级' AFTER `role_type`,
  ADD COLUMN `domain_code` varchar(32) NOT NULL DEFAULT 'core'
      COMMENT 'core=跨业态角色；业态值=仅该业态门店可用' AFTER `scope_level`;

UPDATE iam_role SET scope_level='PLATFORM' WHERE code='platform.operator';
UPDATE iam_role SET scope_level='TENANT'   WHERE code='tenant.owner';
UPDATE iam_role SET scope_level='STORE'    WHERE code IN ('store.manager','store.cashier','store.finance');
```

### 5.3 为什么不建业态角色（决策 D-1）

**不新建** `store.ktv.manager` / `store.hotel.manager` 这类角色。

理由：
1. 角色数会按「职责 × 业态」爆炸（5 职责 × 6 业态 = 30 个角色）；
2. A380 从 KTV 扩到酒店时，运维要重建一整套角色与授予；
3. 业态是**数据与菜单的过滤维度**，不是**职责维度**。

**替代方案**：`store.manager` 绑到 KTV 门店自动获得 `ktv.*`，绑到酒店门店自动获得 `hotel.*` —— 靠 §7 的「按门店业态过滤 `domain_code`」实现，角色与业态彻底解耦。

### 5.4 建议新增角色（可选，不阻塞 M1–M3）

| 角色 code | scope_level | 说明 |
| --- | --- | --- |
| `tenant.ops` | TENANT | 租户运营：跨店报表 / 会员 / 目录，**不含资金与退款审批** |

现状缺口：`tenant.owner` 是全量权限，缺少介于「老板」与「店长」之间的跨店运营角色。

### 5.5 PLATFORM 作用域与 `platform.operator` 的正确处理（v0.2 新增 · 核心）

#### 5.5.1 事实（实测，见 §5.1）

`platform.operator` 持有 **44 / 44 全部权限码**，绑定行是
`(account_id=1, tenant_id=NULL, organization_id=NULL, store_id=NULL, scope_type='PLATFORM')`
（`V11__seed_operational_iam.sql:77-80`；`V10__iam_platform_scope.sql` 已把 `iam_user_role.tenant_id` 改为可空并引入 PLATFORM 作用域）。
其中 **32 个码的 `scope_level='STORE'`**（27 core + 5 ktv），**12 个是 `TENANT`**。
平台超管 `admin` 就是靠这条绑定取得经营权限（`ACCOUNT_PERMISSION_MODEL.md` L80）。

#### 5.5.2 原方案为什么会在这里自相矛盾

v0.1 的两条规则叠加后会**把现状数据判成非法**：

| 位置 | 原 v0.1 规则 | 与现状的冲突 |
| --- | --- | --- |
| §4.2 回填 | STORE 码 `grantable_levels='TENANT,ORGANIZATION,STORE'` | 不含 `PLATFORM` |
| §8.2 判据 | `ur.scope_type ∈ permission.grantable_levels` | `'PLATFORM' ∉ 'TENANT,ORGANIZATION,STORE'` → **44 条绑定全部非法** |
| §8.3 拦截 | `POST /roles/{id}/permissions` 覆盖式保存时校验 | 任何重存该角色授权 → 400 |
| S-2 CI | 新增码必须有 `grantable_levels` | 现状数据被判不一致 |

最危险的后续动作是"按最保守方式修数据"——直接删掉这 44 条绑定，会立刻打断平台超管的全部经营权限，
正是 `V24` 当初修掉的 403 回归。

**根因**：把两件不同的事混成了一件。

| 概念 | 回答的问题 | 由什么决定 |
| --- | --- | --- |
| `scope_level` | 这个权限**操作的数据在哪一层** | 表结构（权威主键里有没有 `store_id`）—— §2.2 |
| `grantable_levels` | 这个权限**允许被绑到哪些授权作用域上** | 授权策略 —— 本节 |

#### 5.5.3 新模型：六条规则

层级序（左宽右窄）：`PLATFORM > TENANT > ORGANIZATION > STORE`。
「更宽的绑定」= 在更高层级的角色里授予该权限，其作用对象是该层下的全部对象。

| # | 规则 | 说明 |
| --- | --- | --- |
| **R1** | **宽向单调**：`scope_level = L` 的码，绑到任何 **≥ L** 的作用域都合法 | 覆盖现有 `selectPermissionCodes` 的"父级授权下沉"语义（§6.1）。`order.view`(STORE) 绑到 `TENANT`/`ORGANIZATION`/`PLATFORM` 都合法 |
| **R2** | **窄向默认禁止、可显式下放**：绑到 `< L` 的作用域默认非法，只有 `grantable_levels` 显式声明了该层才允许 | 这是 `grantable_levels` 的真正作用：**下放白名单**。`member.pii.view`、`audit.view` 显式下放到 `STORE`；`tenant.currency.manage` 不下放 |
| **R3** | **PLATFORM 一律在册、且必须显式** | 由 R1 推出（PLATFORM 是最宽层）⇒ 44 个码的 `grantable_levels` 都含 `PLATFORM`。这**不是**给平台运营开后门，而是把「PLATFORM 是全域作用域」写进数据。S-4 的"空 = 自身及以下"只作为**未配置时的保守兜底**，CI 不允许真的留空 |
| **R4** | **判定式**：`ur.scope_type ∈ p.grantable_levels`，且 `grantable_levels ⊇ {PLATFORM} ∪ {≥ scope_level 的层} ∪ {显式下放层}` | 前半句判"非法授予"，后半句判"漏配"——两条都做成 CI 断言（§4.3 ①②） |
| **R5** | **PLATFORM 绑定的运行时语义不变** | `selectPermissionCodes` 的 `ur.scope_type='PLATFORM'` 短路分支**保持不动**（`IamSnapshotMapper.java:74-81`）。上下文带 `storeId` 时它就对那家店生效；不带 `storeId` 时它对门店级数据没有落点，由菜单剪枝（§9.2 步骤 a）与下游 `TenantContext.permissions` 403 兜住 |
| **R6** | **"能否创建 PLATFORM 绑定"是平台后台能力，不是权限元数据** | 租户管理员在租户后台不得提交 `scopeType=PLATFORM`，服务端必须显式拒绝（落点见 §8.3 第 3 条） |

#### 5.5.4 修正后的授予合法性矩阵（取代 §8.1）

| 权限 `scope_level` | 允许绑定的 `scope_type` | 依据 |
| --- | --- | --- |
| `PLATFORM` | `PLATFORM` | R1 |
| `TENANT` | `PLATFORM`、`TENANT`（+ 显式下放层：`ORGANIZATION`、`STORE`） | R1 + R2 |
| `ORGANIZATION` | `PLATFORM`、`TENANT`、`ORGANIZATION` | R1 |
| `STORE` | `PLATFORM`、`TENANT`、`ORGANIZATION`、`STORE` | R1 |

> 原 §8.1 中 `TENANT → PLATFORM*` 的 `*` 脚注**删除**：它把"平台运营持有 TENANT 级码"写成例外，
> 而事实是"平台运营持有全部 44 个码"，也不是例外而是常态。现在 `PLATFORM` 出现在每一行，理由统一为 R1。

#### 5.5.5 `platform.operator` 全量持有：维持还是收敛（新决策点 **S-6**）

| 选项 | 做法 | 代价 / 风险 |
| --- | --- | --- |
| **1（推荐）维持全量，把"隐式全量"改成"显式契约"** | 新模型下 44 条绑定**本来就合法**（R1/R3），**零数据改动、零行为变化**。再把 V24 的 `p.status='ACTIVE'` 全量授予固化为**登记规则**：新增 `core` 码必须在同批迁移里显式决定是否授予 `platform.operator`，并加 §4.3④ 的 CI 断言（每个 ACTIVE 码必须至少被一个角色持有） | 平台运营仍持有 `ktv.*` 履约码。但 `contexts()` 已把"全部租户 + 全部启用门店"展开为可选上下文（`PermissionSnapshotProvider.java:154-165`），现状就是平台运营可进任一门店操作——不是本次新引入的风险 |
| **2（更严格）显式白名单收敛** | 只保留 12 个 TENANT 码 + core 经营面，**剔除 `ktv.*`（5 个）**；平台进门店处理业务改走按门店绑定或专门的 `platform.support` 角色，审计更清晰 | 需新增反授种子 + 回归平台运营全部经营页；`ktv.session.*` 会对平台运营 403。**这是产品决策**，技术上两种都能落地 |
| **3（不推荐）给 PLATFORM 加"超管短路"** | `SUPER_ADMIN` 在服务端短路全部权限校验，`platform.operator` 只留管理面 | 推翻 `ACCOUNT_PERMISSION_MODEL.md` L80 既有定案，且给权限快照链开旁路，审计口径变差 |

**建议选 1**；把"是否剔除 `ktv.*`"拆成独立小决策 **S-7** 留给产品拍。
无论选哪个，前置条件都是 R1–R6 落进 DDL 与 CI —— 这部分与选项无关。

#### 5.5.6 平台运营在"两段式侧边栏"里的落位（与 S-7 配套）

`AdminMenuApplicationService` 目前把平台菜单（`scope=PLATFORM`）与租户菜单（`scope=TENANT`）分成两套；
平台运营进入某租户 + 门店上下文后看到的是**租户菜单那一套**（V24 注释确认这是刻意行为）。两段式落地后需明确：

- **进入租户上下文时**：租户段按租户菜单渲染，门店段按 §9.2 剪枝渲染。
  若 S-7 选"收敛"，门店段里的 KTV 专属节点会因 `required_permission` 未命中而**自动被剪掉，无需额外代码** ——
  这正是 §9.2 声明式剪枝的价值。
- **未进租户上下文时**：不渲染租户/门店段，只渲染平台段（现状不变）。

> 相关数据缺口：§3.1 要求"门店级区头部显示业态徽标 + 时区"，而当前上下文响应里**没有 `businessType` / `timezone`**，见 §10.4。

---

## 6. 授权继承语义

### 6.1 现有实现（`IamSnapshotMapper.selectPermissionCodes`）

```sql
SELECT DISTINCT p.code
FROM iam_user_role ur
JOIN iam_role r ON r.id = ur.role_id AND r.status = 'ACTIVE'
JOIN iam_role_permission rp ON rp.role_id = r.id
JOIN iam_permission p ON p.id = rp.permission_id AND p.status = 'ACTIVE'
WHERE ur.account_id = #{accountId}
  AND ur.status = 'ACTIVE'
  AND (ur.effective_from IS NULL OR ur.effective_from <= NOW(3))
  AND (ur.effective_to   IS NULL OR ur.effective_to   >= NOW(3))
  AND (
    ur.scope_type = 'PLATFORM'
    OR (
      ur.tenant_id = #{tenantId}
      AND (#{organizationId} IS NULL OR ur.organization_id IS NULL OR ur.organization_id = #{organizationId})
      AND (#{storeId}        IS NULL OR ur.store_id        IS NULL OR ur.store_id        = #{storeId})
    )
  )
```

**语义解读**：上下文维度为空 = 全放行。因此

- 租户级绑定（`store_id IS NULL`）→ 对名下**所有门店**生效
- 组织级绑定（`organization_id = X, store_id IS NULL`）→ 对 X 组织下**所有门店**生效
- 门店级绑定（`store_id = S`）→ **仅 S** 生效
- 门店上下文的有效权限 = `PLATFORM` ∪ `TENANT` ∪ `ORGANIZATION` ∪ `STORE` 四层并集

**边界确认**：上下文 `organizationId` 为 NULL 时，组织级绑定仍会被包含（`#{organizationId} IS NULL` 短路为真）。这是**有意的从宽**，不是 bug —— 代价是「无组织维度的上下文会看到组织级权限」，但组织级绑定本身也是本租户内的，不放大跨租户范围。

**结论：本方案不改这段 SQL 的继承语义**，只在 §8 追加业态过滤。

### 6.2 上下文展开（`PermissionSnapshotProvider.contexts()`）

租户/组织级绑定会**展开出门店上下文**供选择（`selectActiveStores`），否则运营只有租户级绑定时无法进入库存/开台/订单等需要 `storeId` 的页面。这一段保持不动。

---

## 7. 业态权限的合入（决策 D-2）

`store.manager` 在 KTV 门店要有 `ktv.session.open`，在酒店门店要有 `hotel.stay.checkin`。

### 7.1 方案 a（推荐，v0.2 已补全为可直接落地的 SQL）

`selectPermissionCodes`（`IamSnapshotMapper.java:62-86`）追加业态过滤。**改后原文**：

```java
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT DISTINCT p.code
        FROM iam_user_role ur
        JOIN iam_role r ON r.id = ur.role_id AND r.status = 'ACTIVE'
        JOIN iam_role_permission rp ON rp.role_id = r.id
        JOIN iam_permission p ON p.id = rp.permission_id AND p.status = 'ACTIVE'
        LEFT JOIN tnt_store s ON s.id = #{storeId} AND s.status = 'ACTIVE'
        WHERE ur.account_id = #{accountId}
          AND ur.status = 'ACTIVE'
          AND (ur.effective_from IS NULL OR ur.effective_from <= NOW(3))
          AND (ur.effective_to   IS NULL OR ur.effective_to   >= NOW(3))
          AND (
            ur.scope_type = 'PLATFORM'
            OR (
              ur.tenant_id = #{tenantId}
              AND (#{organizationId} IS NULL OR ur.organization_id IS NULL OR ur.organization_id = #{organizationId})
              AND (#{storeId}        IS NULL OR ur.store_id        IS NULL OR ur.store_id        = #{storeId})
            )
          )
          AND (#{storeId} IS NULL OR p.domain_code = 'core' OR p.domain_code = LOWER(s.business_type))
        """)
```

**四个必须注意的实现细节**（v0.1 的示意 SQL 漏了前两个）：

1. **大小写**：`tnt_store.business_type` 存的是大写（`'KTV'`/`'HOTEL'`，见 `tnt_business_type` 与
   `ReservationControllerWebTest` 的 `"businessType":"KTV"`），而 `iam_permission.domain_code` 是小写
   （`ktv`/`hotel`）。比较必须写 **`LOWER(s.business_type)`**，否则业态过滤对 KTV 门店直接失效。
2. **门店不存在/停用 → fail-closed**：`LEFT JOIN ... AND s.status='ACTIVE'` 在门店行缺失或停用时
   `s` 全为 NULL，业态码被全部过滤、只留 `core`。这是**有意的保守口径**（拿不到业态就不给业态权限），
   需要写成测试用例固定下来，别让后人"顺手改宽松"。
3. **`storeId IS NULL` 必须放行**（`#{storeId} IS NULL OR …`）：租户/组织上下文下 `s` 行为 NULL，
   不加这个短路会把租户级视图的业态权限全丢。
4. **`@InterceptorIgnore(tenantLine = "true")` 保留**：该查询本就要跨租户读（平台上下文），
   JOIN `tnt_store` 不会因此被租户拦截器改写。**不要**给这个 JOIN 单独加租户条件。

**只改这一个查询**：`selectAuthorizationVersion`（`:88-109`）**不需要**改 —— 授权版本按作用域匹配，
与权限集合内容无关。业态变更导致权限集合变化时，靠 §10.3 的缓存失效（而不是版本号）保证正确性。

- **优点**：角色与业态解耦，新增业态不改角色；一条 SQL 解决。
- **代价**：多一个 PK 等值 LEFT JOIN（`tnt_store.id` 主键，成本可忽略）；快照只在缓存未命中时重算（TTL 5 分钟/切上下文 forceRefresh）。

### 7.2 方案 b（不推荐）

`store.manager` 授予全部业态权限，靠菜单与数据范围收敛。
缺点：酒店店长的权限快照里带着 `ktv.*`，违反最小权限；平台侧审计口径难看。

### 7.3 测试清单（D-2 必须一起补）

| 用例 | 断言 |
| --- | --- |
| KTV 门店上下文 | 快照含 `ktv.session.*` / `ktv.server.*` |
| HOTEL 门店上下文 | 快照**不含**任何 `domain_code='ktv'` 的码；`core` 码不受影响 |
| `storeId = NULL`（租户/组织上下文） | `core` + 业态码**都在**（放行语义） |
| `business_type` 大小写 | `'KTV'` / `'ktv'` 两种存量写法结果一致（大小写不敏感） |
| 门店停用 / 门店行缺失 | 只返回 `core`（fail-closed） |
| 平台作用域绑定（`scope_type='PLATFORM'`） | 在 KTV 门店上下文保留 `ktv.*`，在 HOTEL 门店上下文被过滤；租户上下文全保留 |
| 缓存 | 同 key 二次调用命中缓存；`forceRefresh=true` 时重算（切上下文路径） |
| `IamSnapshotMapper` 集成测试 | 租户隔离不被 JOIN 破坏（跨租户门店不应命中） |

### 7.4 落地时机与配套（v0.2 补强）

**M1–M3 不依赖它**，作为 M4 的内容 —— M1–M3 只做菜单分层与权限元数据，不改变权限集合本身。

但 D-2 落地有**两个硬配套，必须同批上线**：

1. **§10.3 的业态变更失效点**（否则业态改了、快照最长 5 分钟不刷新，而菜单已按新业态剪枝）；
2. **§10.5 的业态权威来源**（否则"权限按门店业态过滤、订单按客户端传值落库"会分叉）。

**灰度与回滚**：过滤条件是一个 `AND`，回滚 = 去掉该子句重新发布；上线时重点观测
`403 PERMISSION_DENIED` 与"门店业态非 core 时入口消失"两条指标。

---

## 8. 授予合法性校验

### 8.1 矩阵

**已修正并合并进 §5.5.4**（v0.1 的旧矩阵与 `*` 脚注作废）：层级序 `PLATFORM > TENANT > ORGANIZATION > STORE`，
`PLATFORM` 出现在每一行，依据统一为 R1（宽向单调）。

| 权限 `scope_level` | 允许绑定的 `iam_user_role.scope_type` |
| --- | --- |
| `PLATFORM` | `PLATFORM` |
| `TENANT` | `PLATFORM`、`TENANT`（+ 显式下放层） |
| `ORGANIZATION` | `PLATFORM`、`TENANT`、`ORGANIZATION` |
| `STORE` | `PLATFORM`、`TENANT`、`ORGANIZATION`、`STORE` |

### 8.2 判定口径

授权的合法性判据**不是**「`scope_level` 等于 `scope_type`」，而是 §5.5.3 的 R4：

```
① 合法性：ur.scope_type ∈ permission.grantable_levels
② 完整性：grantable_levels ⊇ {PLATFORM} ∪ {所有 ≥ scope_level 的层} ∪ {显式声明的下放层}
③ 未配置：grantable_levels 为空串 → 一律非法（不是"不限制"）
```

②③ 由 CI 断言强制（SQL 见 §4.3 ①②）。

### 8.3 拦截点（v0.2 落到具体方法与行号）

现状：**授权写路径目前没有任何 scope 校验**。实测三个落点：

| # | 落点 | 现状（实测） | 要补什么 |
| --- | --- | --- | --- |
| 1 | `IamController.assignUserRole`<br>`POST /admin/iam/user-roles`<br>`IamController.java:166-188` | `po.setScopeType(req.scopeType() == null ? "TENANT" : req.scopeType())`（:174）—— **请求体直接决定作用域，无枚举校验、无角色层级校验** | 校验 `req.scopeType()` ∈ `{TENANT,ORGANIZATION,STORE}`（平台侧另见第 3 条）；再校验该 `roleId` 的 `iam_role.scope_level` 与之匹配（§8.2 ①）。非法返回 `400 INVALID_GRANT_SCOPE` |
| 2 | `IamController.assignPermissions`<br>`POST /admin/iam/roles/{roleId}/permissions`<br>`IamController.java:99-116` + `togglePermission` `:137-163` | 直接按 `permissionIds` / `permissionCode` 覆盖式写入，**不校验权限层级与角色作用域是否相容** | 逐条校验 `p.grantable_levels` ⊇ 该角色被绑定的 scope 集合；非法整体拒绝（覆盖式写入必须全有或全无） |
| 3 | 平台作用域创建 | `scopeType='PLATFORM'` 只能由平台侧建立；租户后台入口不得提交该值（R6） | 服务端按调用方上下文判定：非平台上下文提交 `scopeType=PLATFORM` → `403 INVALID_GRANT_SCOPE` |
| 4 | **前端** | 授权界面（`/admin/iam/roles`、`/admin/security`）当前无层级概念 | 层级选择器按 `grantable_levels` 过滤可选项 |
| 5 | **CI** | — | 新增权限码必须带非空 `grantable_levels`（§4.3 ①） |

> ⚠️ `assignPermissions` 是**覆盖式**写入（先 `delete` 再 `insert`，:102-107），并且已经调用
> `permissionSnapshotProvider.evictAll()`（:110）。校验必须放在 `delete` 之前，否则非法请求会先把旧授权删掉。

### 8.4 `platform.operator` 权限矩阵与"新增码登记规则"（v0.2 新增）

**现状矩阵**（回放实测，见 §5.1）：`platform.operator` 44/44、`tenant.owner` 38、`store.manager` 34、
`store.cashier` 15、`store.finance` 5。前四者的差集见 §5.1 表。

**登记规则**（把 V24 的隐式全量变成显式契约）：

1. 新增**任何** ACTIVE 权限码时，必须在**同批迁移**里显式决定它授予哪些预置角色；
   不允许依赖"批量全量授予"语句（V24 那种 `p.status='ACTIVE'` 的写法不得再新增）。
2. CI 断言 §4.3④：每个 ACTIVE 码必须至少被一个角色持有 —— 拦住"新码上线即 403"（V24 的成因）。
3. CI 断言 §4.3②：每条 `iam_user_role × iam_permission` 绑定合法（§8.2 ①）。
4. 若 S-6 选"维持全量"（推荐），把这条现状**写成注释落在 `V24` 的后续说明里**（迁移文件不可改，改注释只能新增迁移），
   并在 `docs/business/ACCOUNT_PERMISSION_MODEL.md` 补一句"platform.operator = 平台全域口径，持有全部权限码"。

---

## 9. 菜单模型与下发契约

### 9.1 `iam_menu` 表

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
  `domain_code`         varchar(32)  NOT NULL DEFAULT 'core',
  `sort_no`             int          NOT NULL DEFAULT 0,
  `required_permission` varchar(255) NULL COMMENT '权限码，逗号分隔=任一命中即可见',
  `required_grant`      varchar(64)  NULL COMMENT '平台能力开关，如 payment.method.wallet',
  `badge`               varchar(32)  NULL,
  `status`              varchar(24)  NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_menu_code` (`code`),
  KEY `idx_iam_menu_scope_domain_sort` (`scope_level`, `domain_code`, `sort_no`),
  KEY `idx_iam_menu_parent` (`parent_code`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS 后台菜单定义';
```

**用 `code` 做父子关联而非 `id`**：种子里可读、可幂等重放、跨环境 ID 不稳定也不影响结构。

> **⚠️ v0.2 新增约束：现有 `AdminMenuItem.code` 不能直接搬进 `iam_menu.code`。**
> 实测存在**跨 scope 重名**：`SVC:42`（PLATFORM，`/admin/platform/payment-methods`）与
> `SVC:65`（TENANT，`/business/payment-methods`）的 `code` 都是 `"paymethod"`；
> 另外 `order`、`store`、`report` 这类取值过于泛化。而 §9.1 的 `uk_iam_menu_code` 是唯一键，直接搬会**建表即冲突**。
>
> 规则：`iam_menu.code` 采用**带作用域前缀的语义码**（`platform.paymethod` / `tenant.paymethod`，
> 或 §9.1 示例的 `store.ops.order`）。旧 `code` 只在 `AdminMenuItem` 响应里存在过，
> 前端仅把它当 `v-for` 的 key 兜底（`AdminLayout.vue:22` 的 `item.id || item.code || item.path`），
> 不构成对外承诺 —— 但**这是对"只动分组、不动 path"硬约束的一处例外**，需要评审确认（见 S-8）。

### 9.2 剪枝算法

```
输入：上下文 (tenantId, organizationId, storeId, businessType) + 权限集合 + 平台能力集合
1. 查 iam_menu（ACTIVE），按 parent_code 建树
2. 剪枝（自底向上；父节点无子则连父一起剪）：
   a. scope_level='STORE' 且上下文无 storeId                      → 剪
   b. scope_level='PLATFORM' 且后台角色非 SUPER_ADMIN/PLATFORM_ADMIN → 剪
   c. domain_code != 'core' 且 != businessType                     → 剪
   d. required_permission 非空且权限集合未命中任一                    → 剪
   e. required_grant 非空且平台能力未授予                             → 剪
3. 按 scope_level 落到 levels[]，段内按 sort_no 排序
4. 无 storeId 时 STORE 段返回空数组
```

### 9.3 接口契约

`GET /admin/menus/v2?scope=TENANT`

```json
{
  "scope": "TENANT",
  "context": { "tenantId": 100, "organizationId": null, "storeId": 3001,
               "businessType": "KTV", "currencyCode": "USD" },
  "levels": [
    { "level": "TENANT", "name": "租户", "contextLabel": "A380集团", "menus": [ ... ] },
    { "level": "STORE",  "name": "门店", "contextLabel": "A380 KTV旗舰店",
      "businessType": "KTV", "timezone": "Asia/Shanghai", "menus": [ ... ] }
  ]
}
```

**兼容**：`GET /admin/menus`（旧平铺）保留且冻结，一个发布周期后删除。前端按 `VITE_MENU_V2` 切换。

### 9.4 一致性校验（CI 必过）

```sql
-- iam_menu.required_permission 里的每个码必须存在于 iam_permission
SELECT m.code, m.required_permission
  FROM iam_menu m
 WHERE m.required_permission IS NOT NULL
   AND EXISTS (
     SELECT 1 FROM (
       SELECT TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(m.required_permission, ',', n.n), ',', -1)) AS code
         FROM (SELECT 1 n UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5) n
        WHERE n.n <= 1 + LENGTH(m.required_permission) - LENGTH(REPLACE(m.required_permission, ',', ''))
     ) t
      LEFT JOIN iam_permission p ON p.code = t.code AND p.status='ACTIVE'
     WHERE p.id IS NULL
   );
```

> 实施时建议改为**启动期校验 + 单测**，不依赖复杂 SQL。目的只有一个：`iam_menu` 不能成为第二份权限事实。

**v0.2 汇总：本方案需要的 CI / 发布门禁断言（共 6 条）**

| # | 断言 | 位置 | 拦住什么 |
| --- | --- | --- | --- |
| 1 | 每条 `iam_user_role × iam_permission` 绑定满足 `scope_type ∈ grantable_levels` | §4.3 ② | 非法授予（含把 TENANT 码下放到门店） |
| 2 | 每个 ACTIVE 码的 `grantable_levels` ⊇ 强制集、且非空 | §4.3 ① | 漏配元数据（新增码默认 `STORE` 悄悄生效） |
| 3 | 每个 ACTIVE 码至少被一个角色持有 | §4.3 ④ | 新码上线即 403（V24 的成因） |
| 4 | `iam_menu.required_permission` 中每个码存在于 `iam_permission` | 本节上方 SQL | 菜单变成第二份权限事实 |
| 5 | `iam_menu.required_grant` 中每个能力在 `tenant_payment_method` 语义内（见 §9.2 规则 e） | 启动期校验 | 能力开关写错导致入口永久隐藏 |
| 6 | **菜单 `path` 与前端路由表一致**：叶子节点 `path` 必须命中 `gv_saas_admin/src/router/index.js` 的路由；分组节点 `path` 必须为空 | 跨仓脚本 | 菜单指向不存在的路由 / 路由无入口（违反"path 全部可达"验收项） |

> 第 6 条的实测背景：现有 `AdminMenuItem` 里 PLATFORM 的 `iam`（`SVC:38`）带 `path="/admin/iam"`，
> 而前端**没有**这条路由（只有 `/admin/iam/roles`、`/admin/iam/permissions`）。旧实现靠
> `el-sub-menu`（`SidebarMenuItem.vue:2`）吃掉父节点的 path 才没暴露问题 —— 新模型里应显式规定"分组不带 path"。

---

## 10. 缓存与失效（v0.2 重写：含三处实测修正）

### 10.1 快照缓存实测

| 项 | 实测 | 位置 |
| --- | --- | --- |
| 实现 | Redis 一级 + 本地 `ConcurrentHashMap` 兜底 | `PermissionSnapshotCache.java:26-28` |
| key | `permission:snapshot:` + `accountId:{tenantId}:{orgId}:{storeId}`（空段为空串） | `PermissionSnapshotCache.java:23` + `PermissionSnapshotProvider.java:246-252` |
| TTL | **5 分钟**（`Duration.ofMinutes(5)`） | `PermissionSnapshotCache.java:24` |
| 精确逐出 | `evictByPrefix(accountId + ":")` → 本地 removeIf + Redis `SCAN` | `:64-70`；provider `:224-226` |
| 全量逐出 | `evictAll()` → 本地 clear + Redis `SCAN` | `:72-78`；provider `:232-234` |
| 现有调用点 | `evictAll()` 在 `IamController.java:110`（分配权限）、`:158`（单权限开关）；`evict(accountId)` 在 `:181`（分配用户角色） | — |

**本方案不改变 key 结构** ✓ —— `businessType` 由 `storeId` 唯一决定（一个门店一种业态），`storeId` 已在 key 里。

### 10.2 上下文切换会 forceRefresh（对 D-2 有利）

`snapshot(..., forceRefresh=true)` 跳过缓存重新聚合（`PermissionSnapshotProvider.java:86-97`），
且 `TenantIamDomainClient`（admin→tenant，`:76-91`）与 `TenantServiceClient`（identity→tenant，`:55-67`）
都透传该参数，注释明确写"用于上下文选择"。
⇒ 用户切换/选择上下文后权限立即重算。**D-2 的 SQL 改动不需要等 5 分钟 TTL。**

### 10.3 ⚠️ 修正：`tnt_store.business_type` 目前**没有写路径**

v0.1 写「`StoreApplicationService` 更新 `business_type` 时调用 `evictAll()`」——**这句在现状代码里没有落点**：

| 事实 | 位置 |
| --- | --- |
| `StorePo.businessType` 字段存在 | `infra/persistence/po/StorePo.java:23` |
| `StoreApplicationService` 只写 `timezone` / `business_day_cutoff` | `StoreApplicationService.java:63-104` |
| `PUT /admin/tenant/stores/{id}` 只透传这两个字段 | `StoreController.java:88-98`（`UpdateStoreRequest(timezone, businessDayCutoff)`） |
| 全 `platform-tenant-service` 主代码里 `businessType` 只出现在 `StorePo` 与租户拦截器白名单 | grep 实测 |

⇒ D-2 的配套**不是"补一行代码"，而是一个缺口**。二选一：

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| **A（推荐）** | 在门店配置写路径上**新增"业态"字段**（`UpdateStoreRequest` + `StoreApplicationService`），写入后同事务/同请求调用 `PermissionSnapshotProvider.evictAll()`，并进审计（业态变更影响权限口径，属高风险写） | 一次把"缺写路径"和"缺失效点"一起补上；但引入"门店可随意改业态"的权限问题，需 `tenant.store.manage` 门禁 |
| **B** | 明确业态变更**仍是运维动作**（改库/种子），在运维 SOP 里要求变更后调用一次缓存全量失效（或重启 tenant 服务） | 零代码改动，但把正确性押在流程上，且无审计 |

> 无论 A/B，**§7 的业态过滤都必须与"业态变更失效"一起上线**，否则出现"门店从 KTV 改成酒店后，
> 旧权限快照最长 5 分钟内仍带 `ktv.*`，而菜单已按新业态剪枝"的不一致窗口。

### 10.4 ⚠️ 修正：`businessType` / `timezone` 的上下文缺口（M4 前置）

方案要求门店段头部显示**业态徽标 + 时区**、菜单剪枝按**门店业态**过滤，但实测这两项**当前哪里都没有**：

| 位置 | 现状 |
| --- | --- |
| `SelectContextResponse` | `(tenantId, organizationId, storeId, accountId, authorizationVersion, permissions, currencyCode)` —— **无 `businessType`、无 `timezone`**（`admin/api/context/ContextDtos.java:23-24`） |
| `ContextItem`（上下文列表） | `(contextId, tenantId, tenantName, organizationId, organizationName, storeId, storeName, roles, scopeType)` —— **无 `businessType`**（`ContextDtos.java:9-11`） |
| 前端 `stores/context.js` | 只派生 `tenantId` / `organizationId` / `storeId`（`:67-69`），无 `businessType` |
| 前端 `stores/menu.js` | 直接存后端响应（`menus.value = await getMenus(scope)`，`:28`），无派生 getter |

⇒ **M4 必须先做两件后端改动**（v0.1 把它们当成了前端改动）：

1. `ContextDtos` 的 `SelectContextResponse` / `ContextItem` 增加 `businessType`（门店上下文时非空）；
   建议同时加 `timezone`（`tnt_store.timezone`），供门店段头部显示。
2. `GET /admin/menus/v2` 的 `context` 段补 `businessType` / `timezone`（§9.3 的响应示例已假定有）。

### 10.5 ⚠️ 修正：业态的"双源"问题（D-2 的前置条件）

菜单/权限侧要按 `tnt_store.business_type` 过滤，但**事实表的业态是调用方传的**：

| 事实 | 位置 |
| --- | --- |
| `CreateOrderRequest` 含 `businessType`，直接落库 | `OrderController.java:612`（record）、`:326`（`po.setBusinessType(req.businessType())`） |
| `CreateReservationRequest` 含 `businessType` | `ReservationController.java:233`、`ReservationApplicationService.java:158` |
| 缺失即 400 `BUSINESS_TYPE_REQUIRED` | `ReservationApplicationService.java:1059-1060` |
| order/resource 服务里**没有** `tnt_store` 交叉校验 | grep 实测（只有租户拦截器白名单提到 `tnt_tenant`/`tnt_business_type`） |

⇒ 若不管，"权限按门店业态过滤、订单按客户端传值落库"会分叉（门店是 KTV，客户端传 HOTEL 也能落库）。
**建议（D-2 的前置条件）**：服务端以 `tnt_store.business_type` 为权威，
对入参做**校验或覆盖**（不一致时 400 `BUSINESS_TYPE_MISMATCH`，或直接忽略入参取门店业态）。
这条不属于菜单改造，但属于"业态维度"能不能站住的前提，建议归入 M4 的第一个工作包。

---

## 11. M0 数据地基

门店级权限能否收敛，取决于**门店级数据能否按 `store_id` 可靠切分**。当前 4 个缺口必须先补。

### 11.1 缺口 1：`tnt_pricing_plan` 支持「租户默认价 + 门店覆盖价」

**现状实测**：`V2__tnt_pricing_plan.sql:4` `store_id bigint unsigned NOT NULL`，
唯一键真名是 **`uk_tnt_pricing_tenant_store_type`**（`(tenant_id, store_id, resource_type)`，`V2:16`）。
写侧 `PricingPlanController.create`（`PricingPlanController.java:19-35`）直接取 `req.storeId()`；
读侧在 order 域（`TenantPricingPlanClient`、`DefaultKtvPricingPlanProvider`）与
`InternalPricingPlanController.list`（按 `storeId` 等值过滤，`:27-32`）。

**两种实现，S-1 选哪个决定成本落在哪：**

**A. `store_id` 改可空（需要 DDL）**

```sql
ALTER TABLE `tnt_pricing_plan`
  MODIFY COLUMN `store_id` bigint unsigned NULL COMMENT '门店ID；NULL=租户级默认方案';
ALTER TABLE `tnt_pricing_plan`
  DROP INDEX `uk_tnt_pricing_tenant_store_type`,
  ADD UNIQUE KEY `uk_tnt_pricing_plan_scope` (`tenant_id`, `store_id`, `resource_type`);
```

> ⚠️ MySQL 唯一索引**不把 NULL 判成重复**（多个 NULL 互不相同）⇒ 租户默认方案可能被插入多行，
> 只能靠应用层保证唯一。

**B. `store_id = 0` 表示租户默认（推荐，S-1 的建议值）**

```sql
-- 无需任何 DDL：store_id 保持 NOT NULL，唯一键保持生效（0 是普通值，正常参与判重）
```

> **v0.2 修正**：v0.1 把"缺口 1"整体写成一个 DDL 任务。若采纳 S-1 的 `0` 方案，
> **本缺口的 DDL 部分直接消失**，成本转移到读写逻辑：
> 写侧 `CreatePricingRequest.storeId` 允许 `0`（= 租户默认）；读侧 `InternalPricingPlanController.list`
> 与 order 域的 `DefaultKtvPricingPlanProvider` 改为 `store_id IN (0, :storeId)` 且**门店行优先**。
> 建议采纳 B —— 少一次表结构变更、少一个 NULL 语义陷阱，且与"父级下沉/门店覆盖"的既有范本同构。

**此项需评审确认（S-1）。** 无论 A/B，"租户默认价"都是**新增能力**，需要一篇
`KTV_BUSINESS_*` 侧的消费口径说明（哪些 resource_type 允许门店缺省回退）。

### 11.2 缺口 2：派生子表补 `store_id` + `business_type`

对象：`ord_order_item`、`ord_ktv_session`、`ord_ktv_server_session`、`pay_collect`、`pay_refund`、`pay_transaction`。

**三阶段灰度（不可跳过）：**

```
阶段 1  加可空列 + 双写
        ALTER TABLE ord_order_item ADD COLUMN store_id bigint unsigned NULL AFTER tenant_id,
                                  ADD COLUMN business_type varchar(32) NULL AFTER store_id;
        写入路径：订单创建时从 ord_order 带上（同事务，不可变）
        读取路径：仍走 JOIN（不改读逻辑）

阶段 2  回填历史
        UPDATE ord_order_item i JOIN ord_order o ON o.id = i.order_id
           SET i.store_id = o.store_id, i.business_type = o.business_type
         WHERE i.store_id IS NULL;
        （分批次，每批限制行数，避免长事务；pay_* 经 order_id / payment_intent_id 关联）

阶段 3  校验后置 NOT NULL
        校验 SQL 见 11.4；非空率 100% 且与 ord_order 一致后：
        ALTER TABLE ord_order_item MODIFY COLUMN store_id bigint unsigned NOT NULL,
                                  MODIFY COLUMN business_type varchar(32) NOT NULL;
        加索引：KEY idx_ord_order_item_store (tenant_id, store_id, created_at)
```

**风险**：阶段 1–2 期间新旧数据并存，任何**直读**新列的逻辑必须容忍 NULL。因此**阶段 1–2 不允许改读逻辑**，只在阶段 3 之后切换。

### 11.3 缺口 3/4

```sql
-- 缺口 3：审计加门店维度
ALTER TABLE `iam_audit_log`
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '门店ID；平台级/租户级动作为 NULL' AFTER `tenant_id`,
  ADD KEY `idx_iam_audit_tenant_store_created` (`tenant_id`, `store_id`, `created_at`);
-- 写入路径：AuditContextFilter 从 TenantContext 取 storeId

-- 缺口 4：营销适用范围（原注释「首发骨架不建」）
CREATE TABLE `mkt_campaign_scope` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id`     bigint unsigned NOT NULL,
  `campaign_id`   bigint unsigned NOT NULL COMMENT 'mkt_campaign.id',
  `scope_type`    varchar(16) NOT NULL COMMENT 'ALL/BUSINESS_TYPE/STORE',
  `business_type` varchar(32) NULL,
  `store_id`      bigint unsigned NULL,
  `created_at`    datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mkt_campaign_scope_campaign` (`campaign_id`, `scope_type`),
  KEY `idx_mkt_campaign_scope_store` (`tenant_id`, `store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='营销活动适用范围';
```

### 11.4 校验 SQL（纳入 CI / 发布门禁）

```sql
-- 一致性：子表 store_id 必须与主表一致
SELECT COUNT(*) AS mismatch FROM ord_order_item i
  JOIN ord_order o ON o.id = i.order_id
 WHERE i.store_id IS NULL OR i.store_id <> o.store_id OR i.business_type <> o.business_type;
-- 期望：0

-- 覆盖率：非空率必须 100%
SELECT 'ord_order_item' t, SUM(store_id IS NULL) null_cnt, COUNT(*) total FROM ord_order_item
UNION ALL SELECT 'pay_refund',    SUM(store_id IS NULL), COUNT(*) FROM pay_refund
UNION ALL SELECT 'pay_transaction', SUM(store_id IS NULL), COUNT(*) FROM pay_transaction
UNION ALL SELECT 'pay_collect',   SUM(store_id IS NULL), COUNT(*) FROM pay_collect;
-- 期望：null_cnt 全为 0

-- 权限码核对：代码 PermissionGuard 引用但未入 iam_permission 的码
-- （用 grep 出全部 @RequiresPermission / PermissionGuard 的码，与 iam_permission 做差集；
--   建议做成构建期脚本，避免新增接口漏登记权限）
```

### 11.5 Flyway 脚本按模块编号（v0.2 修正：版本号**不是全局**的）

实测：每个服务模块**各自**有 `src/main/resources/db/migration`，版本号**独立递增**。
v0.1 在 `01_ADMIN` §11 写的 `V28__order_children_store_scope.sql` / `V29__seed_iam_menu.sql` /
`V30__iam_permission_scope_metadata.sql` / `V31__seed_iam_menu_required_permission.sql`
这套全局连号**不成立** —— 例如 `platform-order-service` 的 V28 已被
`V28__ord_order_idempotency.sql` 占用。

| 模块 | 现有迁移数 | 当前最大 | 下一个可用 |
| --- | --- | --- | --- |
| `platform-order-service` | 28 | **V28**（已占） | **V29** |
| `platform-tenant-service` | 27 | V27 | **V28** |
| `platform-admin-service` | 6 | V6 | **V7** |
| `common-payment-service` | 10 | V10 | **V11** |
| `common-audit-service` | 4 | V4 | **V5** |
| `platform-marketing-service` | 4 | V4 | **V5** |

**M0–M3 脚本落位（建议命名，随实际排期微调）**：

| 里程碑 | 模块 | 建议脚本 |
| --- | --- | --- |
| M0 缺口 2 | `platform-order-service` | `V29__ord_children_store_scope.sql`（`ord_order_item` / `ord_ktv_session` / `ord_ktv_server_session`） |
| M0 缺口 2 | `common-payment-service` | `V11__pay_children_store_scope.sql`（`pay_collect` / `pay_refund` / `pay_transaction`） |
| M0 缺口 3 | `common-audit-service` | `V5__iam_audit_log_store_id.sql` |
| M0 缺口 4 | `platform-marketing-service` | `V5__mkt_campaign_scope.sql` |
| M0 缺口 1（**仅方案 A 需要**） | `platform-tenant-service` | `V28__tnt_pricing_plan_store_nullable.sql` |
| M2 | `platform-admin-service` | `V7__iam_menu.sql`（建表 + 种子） |
| M3 | `platform-tenant-service` | `V28__iam_permission_scope_metadata.sql` + `V29__..._backfill.sql` |

> ⚠️ **`iam_menu` 的模块归属需要定**（v0.1 没写）：菜单读取在 `platform-admin-service`
> （`AdminMenuController` + `GET /admin/menus`），而权限元数据在 `platform-tenant-service`。
> 二选一：
> **(a) 建在 `platform-admin-service`（推荐)** —— 菜单是 admin 域自己的数据，剪枝所需的权限集合
> 通过已有的 `TenantIamDomainClient.permissions(...)`（`TenantIamDomainClient.java:80-109`，走
> `/internal/iam/account/{id}/permissions`）取，不新增跨服务接口；
> **(b) 建在 tenant 服务** —— admin 需新增一个 `/internal/...` 读接口。
>
> **同一模块内连号，不跳号、不复用**：若 M0 采纳 S-1 的 `0` 方案（§11.1-B），tenant 服务 M3 直接用 V28。

---

## 12. 服务端工作包

| 里程碑 | 模块 | 工作 |
| --- | --- | --- |
| **M0** | `platform-order-service`(V29)、`common-payment-service`(V11)、`common-audit-service`(V5)、`platform-marketing-service`(V5)、`platform-tenant-service`(V28，仅方案 A) | 缺口 1–4 的 DDL + 双写 + 回填 + 校验（§11）；脚本编号见 §11.5 |
| **M1** | `platform-admin-service` | `AdminMenuApplicationService` 重组树（暂硬编码），`parentId`/`children` 正确填充；**19 项**（不是 18，见 `01_ADMIN` §3.4 v0.3 修正） |
| **M2** | `platform-admin-service` | `iam_menu` 建表 + 种子（V7）；`menusV2()` 查表 + 剪枝；旧 `GET /admin/menus` 冻结保留 |
| **M3** | `platform-tenant-service` | `iam_permission` / `iam_role` 加列 + 回填（§4）；`grantable_levels` 按 §4.2 规则生成；菜单 `required_permission` 化 |
| **M4** | `platform-admin-service`（上下文补字段）、`platform-tenant-service`（业态过滤 + 失效点）、`platform-order-service` / `platform-resource-service`（业态权威来源） | **前置**：§10.4 上下文补 `businessType`/`timezone`；§10.5 业态权威来源校验/覆盖。**主体**：§7 业态过滤 + §10.3 业态变更失效点 |
| **M5** | `platform-tenant-service`（`IamController`）、`gv_saas_admin`（授权界面） | §8.3 的 3 个写路径校验 + `INVALID_GRANT_SCOPE` 错误码 + 批量多门店绑定 `POST /admin/iam/bindings` |
| **贯穿** | CI | §9.4 的 6 条断言 + §4.3 的 4 条 SQL，作为构建期/发布门禁 |

### 测试清单（v0.2 补细节）

| 测试 | 覆盖 |
| --- | --- |
| `AdminMenuApplicationServiceTest` | 树形结构断言（对称性、无孤儿父、排序）+ 剪枝 a–e + 父节点无子被剪。⚠️ **现有 4 个用例里 2 个是位置耦合断言**（`currency` 紧跟 `store`、`order-manage` 紧跟收银台，`TEST:62-63/109-110`），且全部依赖 `menus.indexOf(...)` —— **M1 重组必然打破**，需同步改写为"同域内相邻/顺序"断言；另现有唯一树形断言是 `currency.children().isEmpty()`（`TEST:60`），需扩到剪枝与嵌套 |
| `PermissionSnapshotProviderTest` | 四层并集、`storeId` 为 NULL 放行、业态过滤（M4，用例见 §7.3）、快照缓存 key、`forceRefresh` |
| `IamSnapshotMapper` 集成测试 | 新增 SQL 的租户隔离与作用域匹配（含 JOIN `tnt_store` 后不被租户拦截器改写） |
| `IamController` 写路径校验测试 | §8.3 第 1/2/3 条：非法 `scopeType`、非法权限层级、租户上下文提交 PLATFORM → 400/403 |
| `AuditContextFilterTest` | 审计 `store_id` 落库（M0） |
| 一致性校验脚本 | §4.3 四条 + §9.4 六条作为发布门禁 |

---

## 13. 回滚与兼容

| 变更 | 回滚方式 | 可逆性 |
| --- | --- | --- |
| `iam_menu` 建表 | `DROP TABLE`；旧接口未动 | ✅ 完全可逆 |
| `iam_permission` / `iam_role` 加列 | 列保留不用即可（不影响旧逻辑）；语句本身可 `DROP COLUMN` | ✅ 完全可逆 |
| **`grantable_levels` 回填（§4.2 规则生成）** | **零行为变化**（修正的是元数据，不是数据）；回滚 = 不执行新迁移 | ✅ 完全可逆 |
| 菜单 `required_permission` 化 | 保留后端 4 条硬编码 if 一个周期，开关切换 | ✅ 可切换 |
| 子表补 `store_id`（M0 阶段 1–2） | 列可空、读路径未改，**直接停双写即可** | ✅ 可逆 |
| 子表置 `NOT NULL`（M0 阶段 3） | `MODIFY` 回可空 | ⚠️ 数据已回填，结构可逆 |
| §7 业态过滤 | 去掉 SQL 上那一个 `AND` 子句 | ✅ 完全可逆 |
| §10.3 业态变更失效点（方案 A） | 新增的门店业态字段可停用；`evictAll()` 调用保留无害 | ✅ 可逆 |
| §10.4 上下文补 `businessType`/`timezone` | 响应加字段，纯加法；旧前端忽略未知字段 | ✅ 完全可逆 |
| **S-6 选"收敛"（剔除 `ktv.*`）** | 需保留一份"全量授权"回滚种子（`V24` 同样的全量语句） | ⚠️ 可逆但要重跑种子 |
| **`iam_menu.code` 重命名（S-8）** | 旧 code 只作前端 `v-for` key 兜底，无外部契约 | ⚠️ 数据可改回，但会触发前端重建列表 |

**合并顺序硬约束**：M0 阶段 3（置 NOT NULL）**不能与任何读路径切换同批发布**。先结构、后逻辑，中间留一个发布周期。

---

## 14. 待评审决策点（本文件相关，v0.2 扩充）

| # | 决策 | 建议 | 影响 |
| --- | --- | --- | --- |
| **S-1** | `tnt_pricing_plan.store_id` 用 `NULL` 还是 `0` 表示「租户级默认」 | **用 `0`**（§11.1-B）—— 规避 MySQL 唯一索引对 NULL 不判重，且**本缺口的 DDL 直接消失**，成本转移到读写逻辑 | 表结构 vs 读写逻辑 |
| **S-2** | 新增权限码默认 `scope_level` | **强制显式**：回填后 `ALTER COLUMN ... DROP DEFAULT`（§4.1），strict 模式下漏配即插入失败；CI 兜第二道 | 漏配风险 |
| **S-3** | 子表 `store_id` 回填是否需要停机窗口 | **不需要**，三阶段灰度可在线 | 发布编排 |
| **S-4** | `grantable_levels` 空值语义 | **空串 = 未配置 = 非法**（不是"不限制"，也不是"自身及以下"靠推断）；强制集由 §4.2 规则生成并显式写库 | 授权界面可选项 |
| **S-5** | 是否把 `res_resource.resource_type` 升为字典表 | **建议升**（对应 `01_ADMIN` D-11） | 业态扩展成本 |
| **S-6** | `platform.operator` 维持 44 全量（选项 1）还是收敛剔除业态码（选项 2） | **维持全量**（§5.5.5 选项 1）：零数据改动、零行为变化；把"隐式全量"改成"显式登记 + CI 断言" | 是否动现有授权数据 |
| **S-7** | 平台运营在门店上下文里是否可见/可用业态履约菜单（`ktv.*`） | 与 S-6 配套：选 1 则可见；选 2 则靠 `required_permission` 未命中**自动剪掉**，无需额外代码 | 平台运营体验 |
| **S-8** | 是否允许重命名 `AdminMenuItem.code`（`iam_menu.code` 需要唯一 + 语义化） | **允许**：现有 code 有跨 scope 重名（两个 `paymethod`），唯一键要求必须换名；前端只把它当 `v-for` key，无外部承诺 | 与"只动分组不动 path"硬约束的关系 |
| **S-9** | 业态的权威来源：客户端传值（现状）还是 `tnt_store.business_type` | **门店配置为权威**：入参与门店不一致时 400 或直接覆盖（§10.5）。否则 D-2 会分叉 | 业态维度能否站住 |
| **S-10** | 业态变更的落地方式 | **新增门店配置写路径 + 同请求 `evictAll()`**（§10.3 方案 A），并进审计 | 是否引入业态可改能力 |

> 原 v0.1 的 S-1…S-5 编号保留（S-1/S-2/S-4 的**措辞已按实测修正**），S-6…S-10 为 v0.2 新增。

---

## 15. 变更记录

| 日期 | 版本 | 变更 |
| --- | --- | --- |
| 2026-09-21 | v0.1（评审稿） | 首版。从 `01_ADMIN.md` 拆出服务端规格，补充：44 个存量权限码全量归属表（实测）、`grantable_levels` 跨层授予依据（`platform.operator` 持有 TENANT 级权限）、业态过滤 SQL（D-2 方案 a）、M0 四缺口的 DDL 与三阶段灰度、缓存失效配套、CI 校验 SQL、本文件 5 项决策点 |
| 2026-09-22 | **v0.2（评审稿 · 实测复核）** | **按迁移种子与主代码逐行复核后修正 7 处事实错误并细化 4 个方案**：<br>① **`platform.operator` 持有 44/44 全量权限（不是 3 个 TENANT 级）**，原判据会把现状绑定全判非法 ⇒ 新增 §5.5「PLATFORM 作用域的正确处理」（R1–R6 六条规则 + 修正矩阵 + S-6/S-7）；<br>② `grantable_levels` 语义改为**绑定作用域白名单**，44 个码全部显式含 `PLATFORM`（§4.2 规则化生成，§4.3 四条验收 SQL）；<br>③ §3.3 标题 26 → **27**（与 §3.5 对齐）；<br>④ 新增 §3.6「权限码形态实测」：**2～3 段**、`code` 为权威、`module/resource/action` 不可反推 `code`；<br>⑤ §7 D-2 给出**可直接落地的 `@Select` 原文**，并修正大小写（`LOWER(s.business_type)`）、fail-closed、`storeId IS NULL` 放行、拦截器四细节 + 8 条测试用例；<br>⑥ §10 重写：实测缓存参数、`forceRefresh` 路径，**修正"`StoreApplicationService` 更新业态时 evictAll"（该写路径根本不存在）**，新增 §10.4 上下文缺 `businessType`/`timezone`、§10.5 业态双源三处缺口；<br>⑦ 新增 §11.5 **Flyway 按模块编号表**（原全局 V28–V31 连号不成立，order 服务 V28 已占）；<br>⑧ §8.3 把校验落到 `IamController` 三个具体方法与行号；§9.4 CI 断言汇总为 6 条；§14 决策点 5 → **10 项**（新增 S-6…S-10） |
