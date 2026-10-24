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

## 真实 API 证据（2026-10-24）

```text
POST /admin/apps {"appId":"saas-hotel-c","clientType":"C"}
-> 200 {"id":1,"tenantId":100,"appId":"saas-hotel-c","clientType":"C","status":"ACTIVE"}

GET /admin/apps
-> 200 [{"id":1,"tenantId":100,"appId":"saas-hotel-c","clientType":"C","status":"ACTIVE"}]

GET /admin/entitlements
-> 200 [{"featureCode":"HOTEL","status":"ACTIVE","source":"MANUAL"},
        {"featureCode":"KTV","status":"ACTIVE","source":"STORE_DERIVED"}]

GET /auth/apps?appId=saas-a380-c（真实 C OAuth 会话）
-> 200 {"items":[{"appId":"saas-hotel-c","clientType":"C","featureCode":"HOTEL"}]}
```

部署：tenant V11 已应用，`tnt_app_installation` 存在；identity `/auth/apps` 已部署。

注意：gateway `/api/v1/auth/apps` 路由已加入 `application.yml`，但 gateway 镜像尚需重新部署后经 30080 验证。

## 卸载与审计（2026-10-24）

```text
DELETE /admin/apps/saas-hotel-c -> 200
GET /admin/apps -> [{"appId":"saas-hotel-c","status":"UNINSTALLED"}]
GET /auth/apps?appId=saas-a380-c -> {"items":[]}
open_audit.audit_log:
  iam.app.install   app_installation SUCCESS
  iam.app.uninstall app_installation SUCCESS
```

## 网关

```text
GET http://127.0.0.1:30080/api/v1/auth/apps?appId=saas-a380-c
-> 200 {"items":[{"appId":"saas-hotel-c","clientType":"C","featureCode":"HOTEL"}]}
```

gateway 镜像已重新部署，`/api/v1/auth/apps` 路由生效。

## 权益开通（2026-10-24）

```text
POST /admin/entitlements {"featureCode":"HOTEL2","status":"ACTIVE"}
-> 200

GET /admin/entitlements
-> HOTEL ACTIVE, HOTEL2 ACTIVE, KTV STORE_DERIVED
```

审计动作：`iam.entitlement.grant`。

## S4 仲裁修复（2026-10-24）

```text
/auth/apps 增加当前用户 app grant 过滤（C 端）；未授权不返回
安装经 identity /internal/identity/apps/{appId} 校验注册表与 clientType
权益改为平台 /admin/platform/entitlements；租户侧只读
S3-APP-REGISTRY-001 延续为 S4-APP-REGISTRY-CONTINUE-001（S5 关闭）
```

## 用户授权过滤（2026-10-24）

安装 `saas-hotel-c` 且权益 HOTEL ACTIVE，但当前 C 用户没有 app grant：

```text
GET /auth/apps?appId=saas-a380-c -> {"items":[]}
```

写入 `tnt_app_user_authorization(account_id=112, app_id=saas-hotel-c, status=ACTIVE)`：

```text
GET /auth/apps -> [{"appId":"saas-hotel-c","clientType":"C","featureCode":"HOTEL"}]
```

授权改为 REVOKED：

```text
GET /auth/apps -> {"items":[]}
```

## 安装目录校验（2026-10-24）

```text
POST /admin/apps {"appId":"evil-app","clientType":"C"}
-> 400 APP_NOT_AVAILABLE

POST /admin/apps {"appId":"saas-hotel-c","clientType":"B"}
-> 400 APP_CLIENT_TYPE_MISMATCH（单测覆盖）
```

## 权益权限（2026-10-24）

```text
租户 POST /admin/entitlements 已移除；租户侧只读
平台 POST /admin/platform/entitlements
  需要 PLATFORM 上下文 + platform.tenant.entitlement.manage
租户自助 grant -> 403 ENTITLEMENT_PLATFORM_ONLY（单测覆盖）
```

## 平台权益真实证据（2026-10-24）

平台 `admin` 登录后，平台上下文 token 含 `platform.tenant.entitlement.manage`：

```text
POST /admin/platform/entitlements
{"tenantId":100,"featureCode":"HOTEL3","status":"ACTIVE","source":"SUBSCRIPTION"}
-> 200
```

DB：

```text
tnt_business_entitlement: HOTEL3 ACTIVE SUBSCRIPTION
```

租户 token 调用同一端点：

```text
-> 403 PLATFORM_CONTEXT_REQUIRED
```

审计：

```text
iam.entitlement.grant resource_id=HOTEL3
```

V12 已 seed 权限并授予 `platform.operator`。
