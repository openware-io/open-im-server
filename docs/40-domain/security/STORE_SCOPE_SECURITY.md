# 门店接口权限与数据域约束

## 两层控制，缺一不可

1. **功能权限**：`PermissionGuard.require("...")` 在接口入口校验权限码。
2. **门店数据域**：`StoreScopeRequestFilter` 在 Servlet 层把门店上下文的 `storeId` 收敛到当前门店，跨店参数直接 403。

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
- 查询/写入使用当前门店上下文，或接收 `storeId` 参数并由 `StoreScopeRequestFilter` 注入；
- 添加“无权限 403”和“跨门店 403”测试；
- 不允许 `storeId` 为空时退化为租户全门店查询。

## 现有边界

`StoreScopeRequestFilter` 只处理 query 参数 `storeId` / `storeIds`。请求体中的 storeId 不能作为权限依据；服务必须使用签名上下文。SQL 层的门店条件仍由各业务仓储显式保证，新增表必须在查询中带 `store_id`，并通过集成测试证明跨店不可见。
