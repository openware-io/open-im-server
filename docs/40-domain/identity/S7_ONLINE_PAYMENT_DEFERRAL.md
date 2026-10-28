# S7 在线支付/退款绕过项（单列）

> 状态：`deferred`（用户明确要求先绕过，后续单独处理）
> 日期：2026-10-24
> 适用：S7 验收与本地/测试环境

## 1. 决定

用户明确要求：

```text
在线支付渠道先绕过
做备注，单列这个绕过项
后续再处理
先解决其他剩余问题
```

因此 S7 本次按 **cash-only 条件验收**，在线支付/在线退款不纳入本次 PASS 判定，
但保留为生产前硬门禁，不得当作“已完成”。

## 2. 绕过的功能

```text
BROWSER-C-010 C 在线支付（真实测试商户）
BROWSER-C-012 C 取消/在线退款状态
BROWSER-B-017 B 在线退款申请
BROWSER-B-018 B 在线退款同步/账实
APP-012 Android 在线支付
APP-026 Android 在线退款申请/同步
```

不绕过但必须保持：

```text
C/B 现金收款
B 线下退款申请/审批/REFUNDED
订单 paid/refundable 回冲
超退 422
C/B、租户/门店隔离
```

## 3. 当前环境事实

```text
pay_channel_config:
ALIPAY enabled=0
WECHAT enabled=0
STRIPE enabled=0

pay_channel_provider: 无记录
无真实商户密钥
```

禁止 mock；没有真实渠道时不能伪造在线支付成功。

## 4. 风险

```text
1. 上线后若直接开放 C 在线支付，可能没有可用渠道/回调/对账
2. 用户只能到店现金支付，C 端转化受限
3. 在线退款能力缺失，客诉需线下处理
4. 对账、渠道手续费、退款时效无法验证
5. 不能宣称“完整支付能力已交付”
```

## 5. Owner / 到期 / 生产门禁

```text
决策人：项目负责人（用户，2026-10-24 明确指示）
执行 owner：platform-order-service + common-payment-service
到期：2026-12-31 或生产发布前，以先到者为准
```

生产发布前必须完成：

```text
启用至少一个真实渠道（ALIPAY/WECHAT/STRIPE test merchant）
pay_channel_provider + merchant 配置
C 在线支付 -> 回调 -> 订单 PAID
B/C 在线退款 -> 渠道退款 -> REFUNDED
支付/退款对账
BROWSER-C-010/012、BROWSER-B-017/018、APP-012/026 复跑
```

未满足则生产发布硬门禁阻止。

## 6. 本次 S7 判定

```text
S7 cash-only 条件通过（若其他 P0/P1 全部通过）
S7 完整 PASS：不成立
```

本文件是绕过记录，不是在线支付完成证据。
