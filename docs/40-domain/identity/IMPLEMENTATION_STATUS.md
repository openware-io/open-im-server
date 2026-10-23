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
| S4 应用安装/权益/入口 | 未开始 | — |
| S5 酒店门店/C/B 应用 | 未开始 | — |
| S6 旧模型迁移/冻结 | 部分 | 旧写 410；`iam_user_role` 只读窗口/删除未完成 |
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
