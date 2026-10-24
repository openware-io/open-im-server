# S7 发布门禁（完整 S7 未签字）

> 2026-10-24
> 当前：S7 cash-only E2E CONDITIONAL PASS
> 完整 S7：不成立

## 1. 未完成门禁

### 发布 P1：ACK / ACR / 回滚

需要：

```text
可访问的 ACK 集群 kubeconfig
ACR 推送凭据与目标命名空间
生产/预发域名与证书
回滚演练窗口
```

当前环境只有 Kind `open-im-local`，没有 ACK/ACR 凭据，不能伪造生产发布。

### 发布 P1：正式签名 APK

需要：

```text
正式 keystore（release.jks / keystore.properties）
签名密码
release buildNumber / versionCode
生产 API/WS 域名
```

当前只有 debug APK + 本地 IP，不作为发布包。

### Deferred：在线支付 / 在线退款

见 `S7_ONLINE_PAYMENT_DEFERRAL.md`：

```text
owner：用户
到期：2026-12-31 或生产发布前
需要：真实 ALIPAY/WECHAT/STRIPE 测试商户 + pay_channel_provider
```

未完成前：

```text
不得生产开放 C 在线支付
不得宣称完整支付能力已交付
```

## 2. 已具备

```text
S1–S6 PASS
S7 cash-only E2E CONDITIONAL PASS
SEC-001..014 HAR/DB
B 前台全动作
C H5 下单/订单/取消
Android 同单现金闭环
B 退款/收款 UI
Kind manifest 发布与回滚演练基础
```

## 3. 退出条件

完整 S7 签字需同时满足：

```text
1. ACK/ACR 生产发布成功，digest 与 manifest 一致
2. 生产回滚演练通过
3. 正式签名 APK 安装并跑通核心 C 流
4. 在线支付/退款真实通道 P0 通过（或再次正式签署范围变更）
5. 仲裁复核
```
