# 身份与访问

[中文](README.md) | [English](README.en.md)

> 状态：`accepted target model`（2026-10-23，`develop/2.4.0-hotel`）
> 范围：IM 与 SaaS 账号分域、OAuth 联邦、C/B 成员身份、租户角色、权益、应用安装、平台代操作。
> 决策：[ADR-0015](../../20-architecture/adr/0015-identity-federation-tenant-iam.md)

## 文档

| 文档 | 说明 |
| --- | --- |
| [身份与访问模型](IDENTITY_ACCESS_MODEL.md) | 主体、成员身份、角色、权限、权益、OAuth、会话、工程结构 |
| [身份数据模型](IDENTITY_SCHEMA.md) | 目标 DDL、表归属、唯一键、迁移顺序 |
| [身份 API 契约](IDENTITY_API.md) | OAuth 回调、上下文、权限快照、应用入口、错误码 |
| [落地清单](IDENTITY_ROLLOUT.md) | 迁移、代码结构、应用注册、数据回填、门禁 |
| [改造硬门禁](IDENTITY_GUARDRAILS.md) | 禁止 mock/假成功/绕过鉴权/跨服务读表 |
| [验收测试计划](IDENTITY_TEST_PLAN.md) | 浏览器、真机 App、C/B、安全隔离、异常恢复 |
| [S1 Flyway 证据](S1_FLYWAY_EVIDENCE.md) | 空库/增量迁移记录 |
| [S2 迁移与冻结计划](S2_MIGRATION_PLAN.md) | account_type、客户回填、旧角色绑定迁移 |
| [实施状态](IMPLEMENTATION_STATUS.md) | S1–S7 与 E2E 当前真实状态 |
| [S3 会话证据](S3_EVIDENCE.md) | appId、C/B 隔离、unbind、审计真实证据 |
| [账号权限体系](../ACCOUNT_PERMISSION_MODEL.md) | 历史/现行账号与权限说明；与本文冲突处以本文为准 |

## 核心结论

```text
IM 账号域与 SaaS 账号域分离
OAuth 2.0/OIDC 联邦，不共享账号表/会话
CUSTOMER 与 STAFF 是租户成员身份，不是账号全局类型
角色绑定 membership，不绑定全局 account
平台/租户/门店三级 scope
权益和权限分离
应用安装 + 权益 + 角色权限共同决定可用性
```

## 相关

- [ADR-0014 工作区角色模型](../../20-architecture/adr/0014-workspace-role-model.md)
- [业务域版图](../DOMAIN_MAP.md)
- [平台身份服务](../../50-services/platform/platform-identity-service/README.md)
- [平台租户服务](../../50-services/platform/platform-tenant-service/README.md)
| [S4 应用安装与权益](S4_APP_ENTITLEMENT.md) | 安装、权益、服务入口 |
