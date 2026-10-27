# 门店接口权限、数据域与业态约束

> 上位规范：[权限与数据域规范](../../80-development/standards/50_PERMISSION_AND_DATA_SCOPE.md)  
> 执行门禁：G55 `permission-access-control`

## 三层控制，缺一不可

1. **功能权限**：`PermissionGuard.require("...")` 在接口入口校验权限码。
2. **门店数据域**：`StoreScopeRequestFilter` 在 Servlet 层把门店上下文的 `storeId` 收敛到当前门店，跨店参数直接 403。
3. **业态边界**：`BusinessScopeGuard.require("KTV")` 等校验当前门店业务类型，避免通用店长角色跨业态读取配置和预约。

报表接口是第一个同时具备两层的模块：

```text
/admin/reports/**
  PermissionGuard.require("report.view")
  StoreScopeRequestFilter -> storeId=当前门店
  AdminReportApplicationService -> requireScopedStoreId + SQL 门店筛选
```

## 后续业务接入清单

新增按摩、桌球、零售等业务时，必须同时完成：

- 新增权限码，例如 `massage.order.view` / `billiards.resource.manage`；
- 给对应角色 `iam_role_permission` 授权；
- 菜单 `iam_menu.required_permission` 指向权限码；
- 接口入口调用 `PermissionGuard.require(...)`；
- 接口入口调用 `BusinessScopeGuard.require("MASSAGE"/"BILLIARDS")`；
- 查询/写入使用当前门店上下文，或接收 `storeId` 参数并由 `StoreScopeRequestFilter` 注入；
- 添加“无权限 403”“跨门店 403”“跨业态 403”测试；
- 不允许 `storeId` 为空时退化为租户全门店查询。

## 现有边界

`StoreScopeRequestFilter` 只处理 query 参数 `storeId` / `storeIds`。请求体中的 storeId 不能作为权限依据；服务必须使用签名上下文。SQL 层的门店条件仍由各业务仓储显式保证，新增表必须在查询中带 `store_id`，并通过集成测试证明跨店不可见。

G55 会检查控制器权限守卫、门店过滤器注册、KTV 业态边界，以及控制器使用的权限码是否已在 Flyway 中声明；`AdminControllerPermissionCoverageTest` 作为 Maven 侧第二道防线。
