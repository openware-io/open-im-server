# 身份与 IAM 实施状态

> 分支：`develop/2.4.0-hotel`
> 更新时间：2026-10-24
> 结论：S1–S6 PASS；S7 cash-only E2E **CONDITIONAL PASS**；
> 发布 P1 与在线支付/退款 deferred 未完成，完整 S7 不成立。

## 阶段状态

| 阶段 | 状态 | 证据 |
| --- | --- | --- |
| S1 数据库与领域模型 | PASS | `S1_FLYWAY_EVIDENCE.md`；identity 44 / tenant 131 / customer 228 |
| S2 membership / role_binding | PASS | 仲裁 `c278cc96`；tenant 165；FQCN 0 新增 |
| S3 OAuth C/B 会话 | PASS | 仲裁 `219cbc9d`；真实 C/B 回调、Redis 会话、C↔B 403 |
| S4 应用安装 / 权益 / 入口 | PASS | 仲裁 `09c0923f`；V11/V12、安装/权益、`/auth/apps` |
| S5 酒店门店 / C/B 应用 | PASS | 仲裁 `b3247ddd`；HOTEL 102、C 预订、B 前台/现金退款/夜审/房态 |
| S6 旧模型迁移 / 冻结 | PASS | 仲裁 `b721efc7`；`iam_user_role` 归档、平台角色授予 + PLATFORM_OPERATOR 校验 |
| S7 cash-only E2E | **CONDITIONAL PASS** | SEC-001..014、B 前台全动作、C 取消、Android 同单现金闭环、B 退款/收款 UI |

## 发布门禁 / deferred

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 本地发布 / 回滚 | 进行中 | Kind manifest 发布与回滚演练 |
| Android 测试 APK | 待验证 | 测试包 + 本地 API，不做正式签名 |
| C 在线支付 / 在线退款 | deferred | `S7_ONLINE_PAYMENT_DEFERRAL.md`；生产前硬门禁 |
| 完整 S7 | 不成立 | 在线通道与发布 P1 完成后另签 |

## 浏览器 / 真机

| 范围 | 状态 | 说明 |
| --- | --- | --- |
| 浏览器 B Web | PASS（cash-only） | 登录、门店、酒店前台入住/退房/清扫/夜审/续住/账单、退款/收款 UI |
| 浏览器 C H5 | PASS（cash-only） | 真实目录/报价/下单/我的订单/取消 |
| Android 真机 | PASS（cash-only） | 真实下单 -> B 收款 -> 我的订单 CONFIRMED |
| 隔离 | PASS | C→B 403、B→C 403、篡改 401、跨门店 404 |
| 跨店收款 | PASS | SEC-011 404 / SEC-012 200 |
| 跨店退款 | PASS | SEC-013 404 / SEC-014 200 |
| 现金退款账实 | PASS | 回冲、超退 422、并发保护 |

## 关键证据

- `S7_E2E_STATUS.md`
- `S7_SECURITY_MATRIX.md`
- `S7_REQUEST_EVIDENCE.md`
- `S7_EVIDENCE_INDEX.md`
- `S7_DEPLOYMENT_CORRECTION.md`
- `S7_ONLINE_PAYMENT_DEFERRAL.md`

## 签字口径

```text
S7 cash-only E2E：CONDITIONAL PASS
P0：SEC-001..014 全部闭合
E2E P1：B 退款 UI、Android 同单现金闭环、null-scope、B 收款 UI 已闭合
本地发布/回滚、测试 APK 真机验证 —— 进行中
Deferred：C 在线支付/退款 —— 生产前硬门禁
完整 S7：不成立
```
