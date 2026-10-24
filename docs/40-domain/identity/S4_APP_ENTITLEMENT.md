# S4 应用安装与权益

> 状态：实现中
> 依赖：S3 PASS（219cbc9d）

## 数据

```text
tnt_app_installation
  tenant_id + app_id 唯一
  client_type C/B
  status ACTIVE/SUSPENDED/UNINSTALLED
  installed_by / installed_at / 审计列
```

应用元数据仍以 IM `open_application` 为准，不建重复 `im_open_app`。

权益沿用 `tnt_business_entitlement`，`business_type` 作为 `featureCode`。

## API

```text
GET    /admin/apps
POST   /admin/apps              { appId, clientType }
DELETE /admin/apps/{appId}
GET    /internal/iam/tenants/{tenantId}/apps
GET    /admin/entitlements
GET    /internal/iam/tenants/{tenantId}/entitlements
GET    /auth/apps?appId=        C/B 服务入口
```

`/auth/apps` 返回：

```text
已安装 ACTIVE
∩ 客户端族匹配
∩ 租户 ACTIVE 权益（hotel -> HOTEL，ktv -> KTV；其他 app 不按业务过滤）
```

## 约束

```text
安装/卸载要求 tenant.tenant.manage
写操作审计 iam.app.install / iam.app.uninstall
内部查询跳过租户拦截器，由调用方传 tenantId
未安装应用不返回入口
无权益的 HOTEL/KTV 应用不返回入口
```

## 未完成

```text
应用目录 open_application 同步（S3-APP-REGISTRY-001 过渡）
服务入口前端接入
安装/权益真实 API 证据
```
