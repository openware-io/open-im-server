# 权限与数据域规范

[中文](50_PERMISSION_AND_DATA_SCOPE.md) | [English](50_PERMISSION_AND_DATA_SCOPE.en.md)

> 状态：`current`（`develop/2.4.0`）  
> 层级：L1 安全标准  
> 门禁：G55 `permission-access-control`

## 1. 适用范围

所有后台/业务 HTTP 接口，特别是：

- 租户后台 `/admin/**`；
- B 端经营 `/business/**`；
- 多门店、多业态（酒店、KTV、后续按摩、桌球、零售）共享的接口。

## 2. 四条硬规则

### 2.1 菜单隐藏不是授权

前端菜单、按钮隐藏只影响可见性，不能替代服务端校验。每个业务接口必须在服务端执行权限码校验。

```java
PermissionGuard.require("report.view");
```

权限码必须：

- 在 Flyway 迁移的 `iam_permission` 中声明；
- 授权给对应 `iam_role`；
- 菜单 `required_permission` 使用同一权限码；
- 有“无权限 403”测试。

### 2.2 门店数据域必须由签名上下文决定

客户端传入的 `storeId` 不是权限依据。门店上下文来自签名的 `X-Tenant-Context`：

```text
/admin/** 和 /business/** 由 StoreScopeRequestFilter 统一处理
  无 storeId     -> 注入当前门店
  其它 storeId   -> 403 STORE_SCOPE_FORBIDDEN
  跨店 storeIds  -> 403
```

服务层仍必须显式使用当前门店，禁止 `storeId == null` 时退化为租户全部门店。请求体里的 `storeId` 不享受过滤器注入，业务层必须使用上下文或再次校验。

### 2.3 业态边界必须显式校验

同一个 `store.manager` 角色可能服务于不同业态。仅有角色权限和门店范围不够，还必须校验 `TenantContext.businessType`：

```java
BusinessScopeGuard.require("KTV");
```

规则：

- KTV 配置/预约/开台只允许 `KTV` 门店；
- 酒店前台/预订只允许 `HOTEL` 门店；
- 后续按摩、桌球必须定义自己的 `businessType`，不能复用 KTV 的接口。

### 2.4 SQL 层必须有门店条件

`StoreScopeRequestFilter` 只处理 query 参数，不能替代 SQL 条件。门店级表查询必须带 `store_id`，新增表必须有 `store_id` 字段和跨店不可见测试。

## 3. 控制器审计规则

除认证基础设施外，所有后台业务控制器必须存在以下之一：

- `PermissionGuard.require(...)`；
- 已有的等价权限校验方法（如 `requireManageContext`、`requirePlatformOperator`）；
- 员工管理的 `requireContext` + `iam.role.manage`。

认证基础设施豁免清单固定为：

```text
AdminLoginController
AdminSessionController
SsoAuthController
ContextController
AdminMenuController
AdminBackendController
```

新增业务控制器没有权限守卫时，G55 和 `AdminControllerPermissionCoverageTest` 会失败，必须先补权限和测试。

## 4. 新增业务接入清单

以按摩/桌球为例，必须同时完成：

```text
1. 权限码：massage.order.view / billiards.resource.manage
2. Flyway：iam_permission + iam_role_permission
3. 菜单：iam_menu.required_permission
4. 控制器：PermissionGuard.require
5. 业态：BusinessScopeGuard.require("MASSAGE"/"BILLIARDS")
6. 门店：StoreScopeRequestFilter + 服务层当前门店
7. 测试：无权限 403、跨门店 403、跨业态 403、正常 200
8. 文档：本规范 + 门禁登记
```

## 5. 门禁与证据

- G55 `permission-access-control`：控制器守卫、门店过滤器注册、KTV 业态边界、权限码入库；
- `AdminControllerPermissionCoverageTest`：控制器权限覆盖；
- `StoreScopeRequestFilterTest`：门店注入/跨店拒绝；
- `ReportControllerPermissionTest`：无权限 403；
- `AdminReportStoreScopeTest`：报表跨店拒绝。

## 6. 现有边界

G55 是静态覆盖门禁，不能证明每条 SQL 都正确。请求体中的 `storeId`、复杂联表查询和历史接口仍需安全评审与集成测试。任何“菜单看不到但接口能调”的实现都视为缺陷，不得以兼容层掩盖。
