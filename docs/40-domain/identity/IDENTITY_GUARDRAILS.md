# 身份与授权改造硬门禁

> 适用：ADR-0015 的 S1–S7。
> 原则：真实数据、真实权限、真实失败；不用 mock、不假成功、不绕过边界。

## 1. 绝对禁止

```text
mock 数据
本地假数据兜底
catch 异常后返回空/成功
接口返回假 ID / 假状态
未接后端但按钮显示成功
用 feature flag 跳过鉴权
用测试内存实现替代真实持久化
客户端自报 tenantId/storeId/role/permission
跨服务直接读对方表
跨库 JOIN
用全局 account 直接绑定租户/门店角色
无 tenant 条件的 selectList
无显式条件 + 注释的 @InterceptorIgnore
把线上退款/支付记成现金成功
把 PENDING 当 REFUNDED
把 unpaid 当 paid
把旧 iam_user_role 继续扩展成新模型
先写新表再补读路径（必须 DDL → 回填 → app-aware 读 → 再写）
```

## 2. 允许的兼容，必须登记

```text
OauthLink.app_id = legacy
```

只允许作为旧绑定读路径的迁移标记，必须：

```text
只读兼容
不得新写入 legacy
S4 前完成 app_id 映射
S6 删除 legacy 分支
```

除此之外，不允许“兼容分支”绕过新模型。

## 3. 每阶段必须提交的证据

```text
Flyway 空库迁移输出
Flyway 增量迁移输出
服务单测 + 集成测试
跨租户/跨门店拒绝测试
C/B audience 拒绝测试
真实接口调用记录（非 mock）
审计记录
文档更新
门禁输出
```

## 4. 代码层门禁

```text
domain 不依赖 Spring/MyBatis/Redis/HTTP
api -> application -> domain <- infra
Controller 不写业务规则
Mapper/PO 不出持久化层
角色绑定必须走 iam_role_binding
权限判断必须走 PermissionGuard/统一 PDP
租户查询必须显式 tenant_id 或签名上下文
跨租户查询必须显式 account_id + 注释
支付/退款必须调用真实 payment-service
```

## 5. 数据层门禁

```text
一表一主
一版本一迁移
租户表 tenant_id NOT NULL
唯一键包含 tenant_id
索引对应真实查询
无跨库外键
PII 加密 + 检索摘要
不回改已执行迁移
不把种子/演示数据写进生产迁移
```

## 6. 前端门禁

```text
不保留 mock 页面作为生产入口
不显示未实现功能的成功提示
不硬编码角色/权限/租户
不根据前端菜单决定后端权限
C/B 不共用会话
错误必须透出真实错误码
```

## 7. 仲裁复判清单

```text
是否引入 mock/假数据
是否出现假成功
是否绕过 permission/tenant
是否跨服务读表
是否跨租户查询无显式条件
是否新增第二套模型
是否改已执行迁移
是否只写代码不写迁移/测试/文档
是否门禁被 report-only/例外绕过
```

任一项为“是”，阶段结论直接 FAIL。

## 8. 当前必须盯住的风险

```text
OauthLink legacy 映射未完成
account_type 仍是 EMPLOYEE/CUSTOMER，S2 才能切
tnt_membership 拦截器策略未落代码
旧 iam_user_role 仍存在
open-chat-app 酒店 C 端仍是 mock
既有 flyway-conventions 9 条红灯
既有 tenant-sql RoleMapper 3 条红灯
```

这些是待清偿项，不是“已完成”。

## 9. 相关

- [身份与访问模型](IDENTITY_ACCESS_MODEL.md)
- [身份数据模型](IDENTITY_SCHEMA.md)
- [落地清单](IDENTITY_ROLLOUT.md)
- [ADR-0015](../../20-architecture/adr/0015-identity-federation-tenant-iam.md)
