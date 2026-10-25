# S7 最终交接（2026-10-25）

> 分支：`develop/2.4.0-hotel`
> 结论：S1–S6 PASS；S7 **cash-only CONDITIONAL PASS**；完整 S7 不成立。
> 原因：C 在线支付/在线退款 `deferred`，无真实渠道/商户密钥。
> 本文件是压缩交接入口；细节以链接证据为准。

## 1. 已闭合

| 项 | 状态 | 证据 |
| --- | --- | --- |
| S1 数据库/领域模型 | PASS | `S1_FLYWAY_EVIDENCE.md` |
| S2 membership / role_binding | PASS | `IMPLEMENTATION_STATUS.md` |
| S3 OAuth C/B 会话 | PASS | `IMPLEMENTATION_STATUS.md`、`S7_SECURITY_MATRIX.md` |
| S4 应用安装/权益/入口 | PASS | `IMPLEMENTATION_STATUS.md` |
| S5 HOTEL 门店 C/B | PASS | `IMPLEMENTATION_STATUS.md` |
| S6 旧模型冻结/平台角色 | PASS | `IMPLEMENTATION_STATUS.md` |
| S7 cash-only E2E | CONDITIONAL PASS | `S7_E2E_STATUS.md`、`evidence/` |
| 本地发布 | PASS | `evidence/local-release-final.txt` |
| 后端全服务回滚 | PASS | `evidence/local-rollback-backend.txt` |
| 前端 B/C 回滚 | PASS | `evidence/local-rollback-frontend.txt` |
| Android 真机预订 + 现金收款 | PASS | `evidence/android-hotel-booking-success.png`、`b-collect-order24.png` |
| B/C 路由加载扫描 | PASS | `evidence/b-c-route-sweep-2026-10-25.txt` |
| 全量工程门禁 | PASS | `evidence/engineering-gates-final.txt`（54 gates + Maven full） |

## 2. 当前证据锚点

```text
订单：order24 / store102
预订：HTL1792899679182719
现金：899.00
ord_order：COMPLETED paid 89900
pay_collect：CONFIRMED
htl_booking：CONFIRMED
C/Android 我的消费：O202610250002 已完成
```

```text
Android 测试 APK：2.4.0+6，测试签名，不可用于生产
C 端修复：终态预约不再请求 KTV 取价，/my-reservations 403 -> []
```

## 3. 工程门禁

```text
54 gates + Maven clean verify PASS
例外：
  GATE-FLYWAY-LEGACY-001 9 个已应用迁移，2026-11-15 到期
  service-layout 4 个既有包，2026-12-31 到期
  repository-hygiene 本地工作区目录
```

## 4. 唯一未闭合：在线支付/退款

见 `S7_ONLINE_PAYMENT_DEFERRAL.md`：

```text
pay_channel_config: ALIPAY/WECHAT/STRIPE enabled=0
pay_channel_provider: 无记录
无真实商户密钥
```

恢复步骤：

1. 配置至少一个真实 test merchant 渠道。
2. 写入 `pay_channel_config` + `pay_channel_provider`。
3. 跑 C 在线支付 -> 渠道回调 -> `ord_order.status=PAID`。
4. 跑 B/C 在线退款 -> 渠道退款 -> `REFUNDED` + 账实一致。
5. 更新 `S7_E2E_STATUS.md` 与 `S7_RELEASE_GATE.md`，另签完整 S7。

禁止：

```text
mock 渠道
用现金支付冒充在线支付
伪造回调
用测试签名 APK 冒充生产包
```

## 5. 复验命令

```powershell
.\scripts\validate\invoke-engineering-validation.ps1 -Scope Full

cd D:\projects\cnb-oss\open-saas-admin
npm test

cd D:\projects\cnb-oss\open-saas-mobile
npm test
```

运行态：

```text
B http://127.0.0.1:30080/saas/
C http://127.0.0.1:30080/a380/
Android API http://192.168.3.35:30080
```

## 6. 路径与前端最终状态（2026-10-25）

```text
/saas/  B 管理后台
/b/     B 移动端
/c/     C 端唯一入口
/a380/  已移除，不再作为 C 端入口
```

- `/a380` 已从 ingress、C 静态产物、OIDC redirect、referer、数据库服务链接移除。
- 旧 APK 必须升级；新 APK 已用 `/c` 构建并安装。
- C 端酒店、场馆、机票、打车表单均使用产品底部 picker，原生日期/时间/下拉已清零。
- C 测试：24 files / 168 tests PASS。
- Flutter 测试仍受本机 tester 端口 2985 连接失败影响，未记为 PASS。
