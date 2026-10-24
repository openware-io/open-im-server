# 身份与 IAM 实施状态

> 分支：`develop/2.4.0-hotel`
> 更新时间：2026-10-24
> 结论：S1、S2 已签字；S3 进行中；S4–S7 与浏览器/真机 E2E 未开始。

## 阶段状态

| 阶段 | 状态 | 证据 |
| --- | --- | --- |
| S1 数据库与领域模型 | PASS | `S1_FLYWAY_EVIDENCE.md`；identity 44 / tenant 131 / customer 228 |
| S2 membership/role_binding | PASS | 仲裁 `c278cc96`；tenant 165；FQCN 0 新增 |
| S3 OAuth C/B 会话 | 进行中 | 应用注册表、C/B cookie、STAFF_NOT_BOUND；尚未仲裁 |
| S4 应用安装/权益/入口 | 进行中 | `tnt_app_installation`、安装/权益 API、`/auth/apps` |
| S5 酒店门店/C/B 应用 | 进行中 | HOTEL 门店 102、C 预订 201、B 确认 200 |
| S6 旧模型迁移/冻结 | 进行中 | iam_user_role 已归档；平台角色授予/撤销已真实跑通 |
| S7 测试/部署/门禁 | 未开始 | — |
| 浏览器 E2E | 未准入 | 依赖 S1–S6 |
| 真机 E2E | 未准入 | 依赖 S1–S6、APK、真机可达 30080 |

## S2 已交付

```text
tnt_membership / tnt_staff / iam_role_binding
account_type USER/PLATFORM_OPERATOR + V4
cst_member.membership_id 回填
iam_user_role -> iam_role_binding 迁移
权限快照切 iam_role_binding
旧写方法 410
租户/门店/组织/平台绑定校验
审计、版本单调、快照 evict
```

## S3 已交付

```text
saas.apps.consumer / backend 注册表
OAuth 回调 appId 校验
C/B cookie 按注册表
/auth/contexts 会话 appId 一致性
B 端未绑定员工 403 STAFF_NOT_BOUND
```

## S3 未交付

```text
OAuth 回调后 membership / 租户上下文绑定
C/B token audience/scope 校验
应用维度 token 撤销/解绑
S3 仲裁
```

## 门禁

```text
tenant-sql PASS
gate-registry PASS
docs links/impact/governance PASS
audit-fields PASS
flyway-immutability PASS
FQCN 11 条既有，0 新增
flyway-conventions 9 条：GATE-FLYWAY-LEGACY-001 例外，S5 关闭
```

## 测试

```text
identity 47 PASS
tenant 165 PASS
admin 173 PASS（3 skipped）
customer 228 PASS
```

## 未准入声明

```text
浏览器 B Web/C H5 未跑
Android 真机未跑
酒店预订/支付/退款/前台/夜审/房态 E2E 未跑
没有 mock 证据，也没有 E2E PASS 结论
```

## 下一步

```text
1. S3 补齐 membership/租户绑定与 token audience，交仲裁
2. S4 应用安装/权益/服务入口
3. S5 HOTEL 门店、C/B 应用、酒店全链路
4. S6 旧表只读窗口/删除
5. S7 部署、浏览器、真机 E2E
```

S3 更新：B 未绑定在 callback 阶段 403；C 未绑定 CUSTOMER 在 callback 阶段 403 CUSTOMER_NOT_BOUND（S3 不做首登建客户）；/auth/session|contexts|select appId 必填。


S3 更新：unbind 端点与 REVOKED→ACTIVE 重绑已完成；identity 49 tests。


S3 更新：token clientApp claim + 酒店 B/C audience guard 已完成；identity 50、order guard 2 tests。


S3 更新：consumer 授权撤销 + 权限快照逐出已完成。


## S3 过渡例外：应用注册表

```text
ID：S3-APP-REGISTRY-001
现状：saas.apps.consumer/backend 配置桥，非 IM open_application
已收紧：删除默认列表；空配置拒绝所有 app；同一 app 双登记启动失败
未覆盖：redirect_uri 白名单、scopes、APPROVED/SUSPENDED 状态由 IM token exchange 兜底
负责人：platform-identity-service + platform-tenant-service
到期：2026-12-31
退出：S4 切 open_application/open_application_scope，补 redirect/scopes/status 校验
```

## S3 已知边界

```text
C 端当前建 tnt_membership，但 cst_member 与 consumerContext 切换留到 S4/S5
clientApp 强制已覆盖酒店链路，非酒店 B/C API 的全量 audience 矩阵留到 S4
unbind 尚无独立审计事件
```

### S3 收紧

```text
/auth/session|contexts|select|csrf|logout 全部要求 appId
C 应用未绑定 ACTIVE CUSTOMER membership -> 403 CUSTOMER_NOT_BOUND
B 应用先校验 ACTIVE STAFF membership，再校验运营角色
unbind 要求 X-CSRF-Token
order-service /admin/** 与 tenant-service /admin/** 仅接受 saas-b（无 clientApp 的内部调用兼容）
```

S3 复判修复：

```text
C context 增加 ACTIVE CUSTOMER membership 前置校验；无 membership -> 403 CUSTOMER_NOT_BOUND
OAuth 回调 session 写入 membershipId/tenantId/membershipType
admin-service /admin/**、customer-service /business/** 增加 clientApp 门禁
S3-APP-REGISTRY-001 已登记 ENGINEERING_DEBT_EXCEPTIONS / GATE_REGISTRY
```

S3 复判修复（第二批）：

```text
Referer 改为精确 origin+path 白名单；带会话无 Referer -> 403 CLIENT_ORIGIN_REQUIRED
双 cookie 下 C 页面传 B appId -> 403 CLIENT_ORIGIN_FORBIDDEN
order /business/** B 前端 controller 加 @ClientAudience("saas-b")
C membership 必须匹配 consumerContext 的 app tenant
真实 OAuth C/B 回调、Redis session、token clientApp、C↔B 403 已取证
```

S4 更新：tnt_app_installation、安装/权益 API、/auth/apps 已实现；真实安装与入口返回 200。


S4 更新：网关 /auth/apps、真实安装/卸载/审计、入口空列表已验证。


S4 更新：权益写入口 POST /admin/entitlements 已真实开通 HOTEL2。


S4 更新：用户授权过滤、安装目录校验、平台权益入口已补。


S4 更新：平台权益真实 HTTP 200（HOTEL3/SUBSCRIPTION）+ 租户 403；V12 权限 seed。


S5 更新：C 预订→B 确认→入住→收款→押金→退房→夜审→清扫真实跑通。


S5 更新：现金退款 200，退款后余额拦截/补收后退房已验证。


S5 修复：htl_booking 账号归属 + C 本人订单隔离；酒店迁移脚本；在线支付/退款正式 defer S7。

