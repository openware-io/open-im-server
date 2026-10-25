# SaaS 路径与租户路由标准

[中文](SAAS_PATH_STANDARD.md) | [English](SAAS_PATH_STANDARD.en.md)

> 状态：`current`
> 适用范围：SaaS 管理后台、B/C 移动端、OAuth 回调、网关与 Ingress 路径。

## 1. 原则

```text
业务应用不绑定租户。
路径只标识入口/渠道，不标识租户身份。
租户身份只来自签名会话中的 tenantId + membership/role_binding。
路径不参与授权判断。
```

因此：

```text
禁止把租户 code 写进业务路径。
禁止用路径参数决定当前租户。
禁止为每个新租户新增一条 Ingress 或一套 OAuth client。
```

## 2. 正式路径

```text
/saas/      SaaS 管理后台（平台运营 + 租户后台）
/b/         B 端移动入口
/c/         C 端消费者入口
/api/**     业务 API，不带租户
/oauth/**   OAuth 授权端点，不带租户
```

租户由会话/上下文解析：

```text
C 端：SaaS session -> tenantId
B 端：Admin session -> selected context -> tenantId
```

## 3. 兼容路径

```text
/a380/      历史 C 端品牌入口，保留为兼容别名
```

要求：

```text
/a380/ 与 /c/ 指向同一个 saas-mobile 产物
OIDC redirect_uri 同时登记 /c/ 与 /a380/
旧 APK、旧二维码、旧书签继续可用
新入口、新链接、新文档统一使用 /c/
```

`/a380/` 不得成为新租户的路径模板。

## 4. 租户品牌隔离

默认不区分租户路径。需要品牌隔离时，优先使用子域名：

```text
https://{tenantSlug}.example.com/c/
```

子域名由边缘层映射到 tenantId，但应用仍以会话为准。

如果业务坚持路径隔离，只允许：

```text
/t/{tenantCode}/c/
/t/{tenantCode}/b/
```

约束：

```text
1. 一条通配 Ingress 服务所有租户，不为单租户加路由
2. 边缘层把 tenantCode 解析为 tenantId，并校验租户为 ACTIVE
3. 应用仍以会话 tenantId 为准，路径只做入口提示
4. OIDC redirect_uri 按租户登记，并有明确迁移/回滚表
5. tenantCode 改名必须保留旧 code 的 302/308 兼容
```

不采用：

```text
/{tenantCode}/          根路径直接放租户 code
/a380/                  单租户品牌名作为通用入口
```

## 5. appId 规则

```text
appId 标识客户端应用，不标识租户。
```

示例：

```text
推荐：saas-c、saas-b、saas-hotel-c
不推荐：saas-a380-c、saas-s929-c
```

租户是否可用由：

```text
tnt_app_installation + tenantId + 应用注册表
```

决定。

历史 `saas-a380-c` / `saas-a380-h5` 保留兼容，后续迁移到客户端应用 ID。

## 6. 迁移顺序

```text
1. 新增 /c/ Ingress，保留 /a380/
2. OIDC 同时登记 /c/ 和 /a380/
3. C 端 appId 改为可配置，默认兼容旧值
4. Android/IM 服务入口改 /c/
5. 观察两个入口访问量
6. 旧入口清零后，/a380/ 做 GET 兼容跳转
7. 清理 appId 中的租户/品牌字面量
```

## 7. 验收

```text
GET /c/                  200
GET /a380/               200
C /c/#/service/hotel     console []
C /a380/#/service/hotel  console []
OIDC redirect /c/        已登记
OIDC redirect /a380/     保留
租户改名                  不影响 /c/
新增租户                  不新增 Ingress / OAuth client
```
