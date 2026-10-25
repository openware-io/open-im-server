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

已找到 ACK kubeconfig：`C:\Users\Administrator\.kube\ack-dev-conn.yaml`，context `209277982874572694-c8629a647dce749afbec6d16972bc6ec7`，namespace `im-business`。

但 2026-10-25 实测：

```text
TCP 47.98.170.15:6443 可连接
TLS ClientHello 后服务端返回 0 字节 EOF
kubectl get ns -> context deadline exceeded / EOF
```

因此当前无法执行 ACK 发布与回滚，需先恢复集群 API 或网络白名单。

ACR 仓库：`crpi-2xbf44rg544imbew.cn-hangzhou.personal.cr.aliyuncs.com`；`docker login` 返回 `unauthorized: authentication required`，Docker Desktop 凭据已过期，需重新登录。

没有用 Kind 结果冒充 ACK。

### 发布 P1：正式签名 APK

需要：

```text
正式 keystore（release.jks / keystore.properties）
签名密码
release buildNumber / versionCode
生产 API/WS 域名
```

2026-10-25 已按 `tools/release.ps1 -SigningPolicy AllowUnsigned -SkipPublish` 生成：

```text
app-release.apk
version 2.4.0+6
size 175334996
sha256 230580bb0d9fae9c9adb35763e8668bd8e023feaa57179bba1a171f584f37364
signing unsigned
installable=false
```

该制品只用于构建/上传链路验证，不可安装、不可进入生产渠道。正式签名仍需运营方 keystore + `android/key.properties`。debug APK 仅用于研发真机 E2E。

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
