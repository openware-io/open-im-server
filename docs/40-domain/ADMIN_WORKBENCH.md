# 后台角色工作台与信息架构

[中文](ADMIN_WORKBENCH.md) | [English](ADMIN_WORKBENCH.en.md)

> 状态：`draft`（2026-10-22，待原型评审）  
> 范围：SaaS 后台（平台运营 / 租户总部 / 门店经营 / IM 管理）  
> 原则：角色决定工作台，权限决定可见性，上下文决定数据范围。

## 0. 实施进度

| 阶段 | 状态 | 已落地 |
| --- | --- | --- |
| P0 角色档案 / 登录落点 / 服务端菜单 | ✅ 已上线 | `iam_role_profile`、login/session 返回 roles/workspaces/landing、前端按角色落点 |
| P1 全部门店汇总 | ✅ 已上线 | 经营总览支持全部门店/酒店/KTV/单店范围 |
| P2 角色治理 / 门店范围 | ✅ 基础完成 | 平台/租户角色目录、租户自定义角色、权限矩阵；预置角色只读 |
| P3 onboarding | ✅ 已上线（基础） | 租户总览数据驱动开通清单 |
| P4 业务开通驱动模块 | ✅ 基础完成 | `tnt_business_entitlement` + `/admin/tenant/businesses`，总览禁用未开通业态 |
| P5 动作级权限 | ✅ 已上线 | 酒店前台任务/入住/退房端点，按 `hotel.booking.view`/`hotel.checkin`/`hotel.checkout` 校验 |
| P6 移动端角色/任务对齐 | ✅ 已接入 | identity 员工登录 → B 端会话 → 门店上下文 → 酒店任务 |

### P4-P6 实现入口

```text
P4
  GET  /api/v1/admin/tenant/businesses
  POST /api/v1/admin/tenant/businesses/{businessType}/enable
  表：tnt_business_entitlement

P5
  iam_permission：tenant.business.manage / hotel.*
  前端：v-permission
  后端：BusinessEntitlementApplicationService 校验 tenant.business.manage

P6
  Flutter：/business/role-home
  POST /api/v1/identity/login
  POST /api/v1/auth/session?appId=saas-b
  GET  /api/v1/auth/contexts
  POST /api/v1/auth/context/select
  GET  /api/v1/business/hotel/frontdesk/tasks
  店长 / 前台 / 收银三种任务首页
```

## 1. 问题定义

当前后台把四层能力放在同一套侧栏里：

```text
平台运营
租户总部
门店经营
业务配置
```

结果：

- 平台超管看到租户门店菜单；
- 租户管理员看到门店收银；
- 门店店长看到平台配置；
- 没有门店角色时，门店菜单直接消失；
- 酒店所有配置和运营塞在一个 Tab 页；
- 新增业务只能继续往同一个页面加 Tab。

这不是“切上下文”能解决的，是**角色工作台缺失**。

## 2. 四个工作区

```text
Group IDaaS
  ├─ Platform Console       平台运营
  ├─ Headquarters           集团/租户总部
  ├─ Store Workspace        门店经营
  └─ IM Console             IM 管理
```

| 工作区 | 面向角色 | 数据范围 | 默认首页 |
| --- | --- | --- | --- |
| Platform | 平台超管、平台运营 | 全平台 | `/platform/overview` |
| Headquarters | 租户 owner/admin | 当前租户 | `/tenant/overview` |
| Store | 店长、前台、收银、财务 | 当前门店 | `/store/:storeId/today` |
| IM | IM 管理员 | IM 范围 | `/im/overview` |

一个账号可以拥有多个工作区，但默认只进入一个；顶部“切换工作区”只在有多个角色时出现。

## 3. 角色档案（Role Profile）

角色不能只有 `code + permission`，还需要工作台定义：

```text
iam_role_profile
  role_id
  workspace_code       PLATFORM / HEADQUARTERS / STORE / IM
  landing_route
  default_scope
  default_business_type
  nav_profile
  data_scope
  status
```

示例：

```text
role: store.manager
workspace: STORE
landing: /store/:storeId/today
dataScope: STORE
nav: today, frontdesk, reservations, rooms, cashier, reports, settings

role: store.cashier
workspace: STORE
landing: /store/:storeId/cashier
dataScope: STORE
nav: cashier, bills, shifts

role: tenant.owner
workspace: HEADQUARTERS
landing: /tenant/overview
dataScope: TENANT
nav: overview, portfolio, stores, members, finance, reports, governance
```

## 4. 登录落点

登录响应必须返回：

```json
{
  "roles": ["store.manager"],
  "workspaces": ["STORE"],
  "defaultWorkspace": "STORE",
  "landing": "/store/100/today"
}
```

不再使用“第一个菜单叶子”作为首页。

多工作区账号：

```text
平台超管 + 租户管理员
  → 默认 Platform
  → 顶部可切换 Headquarters
  → 不把两套菜单混在一个侧栏
```

## 5. 角色首页

### 平台超管

```text
平台健康
租户数 / 活跃门店 / 本月新增 / 异常租户
租户列表：套餐、门店数、状态、到期时间
平台告警：支付、消息、存储、审计
```

不出现酒店房态、KTV 房台、门店收银。

### 集团总部

```text
集团经营
营收 / 入住率 / 开台率 / 待处理
业务组合：酒店、KTV、餐饮、健身
门店排名
集团策略告警
```

不出现某家门店的前台入住按钮。

### 酒店店长

```text
今日经营
到店 / 在住 / 待退 / 出租率
今日任务：入住、退房、收款、清扫、维修
房态网格
快捷操作：新预订、入住、结账
```

### 酒店前台

```text
今日任务
待入住 / 待退房 / 待收款 / 待确认
快速办理
```

不展示集团报表、平台配置。

### KTV 店长

```text
今日经营
开台数 / 预约数 / 营收 / 待结账
房台状态
待处理任务
```

## 6. 导航结构

### Platform

```text
概览
租户与套餐
平台权限
支付方式
集成与渠道
审计与健康
```

### Headquarters

```text
经营总览
业务组合
门店与业态
会员与客户
资金与支付
营销
报表
组织与权限
策略中心
```

### Store

```text
今日
前台
预订
房态 / 房台
收银
交班
商品 / 库存
会员
报表
设置
```

酒店门店导航：

```text
今日
前台
预订
房态
房价与库存
客人
报表
设置
```

KTV 门店导航：

```text
今日
房台
预约
收银
交班
商品
报表
设置
```

## 7. 酒店模块拆分

不再使用一个 `hotel.vue` 承载 9 个 Tab。

```text
/store/:storeId/hotel/overview
/store/:storeId/hotel/frontdesk
/store/:storeId/hotel/reservations
/store/:storeId/hotel/rooms
/store/:storeId/hotel/rates
/store/:storeId/hotel/guests
/store/:storeId/hotel/reports
/store/:storeId/hotel/settings
```

租户总部只保留：

```text
/tenant/portfolio/hotel
  酒店门店列表
  集团酒店经营
  集团价格/支付策略模板
```

## 8. 未来业务扩展

新增业态不改后台壳，只注册模块：

```text
businessModule
  code: HOTEL
  name: 酒店
  workspace: STORE
  routeBase: /store/:storeId/hotel
  menuProvider
  dashboardProvider
  capabilities
```

租户总部展示“业务组合”卡片；门店展示“业态切换”，不把所有业态菜单叠在一个侧栏。

## 9. 权限与可见性

```text
Capability  租户是否开通该业务
Permission  角色是否有该操作权限
Context     当前租户/门店/业态是否正确
```

三者取交集：

- 菜单接口不返回无权节点；
- 按钮不渲染无权操作；
- 深链进入无权页面时回到角色首页并说明原因；
- 不出现“先展示、后提示没有权限”。

空态必须可行动：

```text
未开通酒店 → 联系集团管理员
无门店角色 → 联系店长
未选门店 → 选择门店
```

## 10. 迁移计划

```text
P0 角色档案 + 登录落点 + 菜单按工作区返回
P1 平台/总部/门店三个工作区壳
P2 门店角色分配与门店工作台
P3 酒店从单页拆成门店模块
P4 KTV 工作台对齐
P5 业务模块注册表，支持餐饮/健身/零售
```

## 11. 生产入口

原型路由已从生产路由表移除，不再对外暴露 mock 工作台。

实际入口：

```text
平台运营后台  /saas/#/admin/iam/roles
租户角色      /saas/#/admin/roles
门店工作台    /saas/#/store
```

角色、菜单、数据范围由登录 workspace 与 IAM 权限决定。

## 12. 相关

- [业务域版图](DOMAIN_MAP.md)
- [业界对标评估](ADMIN_WORKBENCH_BENCHMARK.md)
- [ADR-0013：角色工作台作为平台能力](../20-architecture/adr/0013-role-workbench.md)
- [酒店实现方案](hotel/HOTEL_IMPLEMENTATION_PLAN.md)
