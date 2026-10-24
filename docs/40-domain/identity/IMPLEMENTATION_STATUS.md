# 身份与 IAM 实施状态

> 分支：`develop/2.4.0-hotel`
> 更新时间：2026-10-24
> 结论：S1–S6 已签字 PASS；S7 未通过/阻塞（在线支付通道缺失）。

## 阶段状态

| 阶段 | 状态 | 证据 |
| --- | --- | --- |
| S1 数据库与领域模型 | PASS | `S1_FLYWAY_EVIDENCE.md`；identity 44 / tenant 131 / customer 228 |
| S2 membership / role_binding | PASS | 仲裁 `c278cc96`；tenant 165；FQCN 0 新增 |
| S3 OAuth C/B 会话 | PASS | 仲裁 `219cbc9d`；真实 C/B 回调、Redis 会话、C↔B 403 |
| S4 应用安装 / 权益 / 入口 | PASS | 仲裁 `09c0923f`；V11/V12、安装/权益、`/auth/apps` |
| S5 酒店门店 / C/B 应用 | PASS | 仲裁 `b3247ddd`；HOTEL 102、C 预订、B 前台/现金退款/夜审/房态 |
| S6 旧模型迁移 / 冻结 | PASS | 仲裁 `b721efc7`；`iam_user_role` 归档、平台角色授予 + PLATFORM_OPERATOR 校验 |
| S7 测试 / 部署 / 门禁 | 未通过/阻塞 | 真实 B/C/Android、隔离、退款账实已过；在线支付通道缺失 |

## 浏览器 / 真机

| 范围 | 状态 | 说明 |
| --- | --- | --- |
| 浏览器 B Web | 部分通过 | a380-admin 登录、A380 Hotel 门店、酒店前台真实任务 |
| 浏览器 C H5 | 部分通过 | 真实目录/房型/报价/下单/我的订单；在线支付未完成 |
| Android 真机 | 部分通过 | 登录成功；真实 A380 Hotel、Deluxe King、¥899 报价；自动下单/支付未完成 |
| 隔离 | PASS | C→B 403、B→C 403、篡改 401、跨门店 404 |
| 现金退款账实 | PASS | 89900→59900，sum(refund)=30000；超退 422 |
| 在线支付/退款 | 阻塞 | ALIPAY/WECHAT/STRIPE enabled=0；无 provider/商户密钥 |

## 关键证据

- `docs/40-domain/identity/S3_EVIDENCE.md`
- `docs/40-domain/identity/S4_APP_ENTITLEMENT.md`
- `docs/40-domain/identity/S5_HOTEL_EVIDENCE.md`
- `docs/40-domain/identity/S6_LEGACY_EVIDENCE.md`
- `docs/40-domain/identity/S7_E2E_STATUS.md`
- `docs/40-domain/identity/S7_DEPLOYMENT_CORRECTION.md`

## S7 阻塞与方案

仲裁结论：`S7 FAIL（BLOCKED by external online payment channel）`。

方案二选一：

```text
A. 提供真实测试商户配置，完成 C 在线支付 / B/C 在线退款 / 回调 / 对账
B. 产品/财务/安全签署 cash-only 范围变更，S7 条件通过并保留生产前硬门禁
```

在 A/B 之前继续可执行项：Android 全 C 流、ERR-001/002、HAR/logcat/trace 证据。
