# 计费与结账域（Billing）

[中文](README.md) | [English](README.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 定位：公共支撑域；负责把业务计费项算成账单/应付金额，不拥有房价、包厢计费、菜单价格等业务规则。  
> 关键：`Billing` 不是 `Checkout`，`Bill` 不是 `Invoice`。

## 1. 术语

| 术语 | 含义 | 归属 |
| --- | --- | --- |
| `Pricing` 定价 | 设定基础价格/房价/套餐价 | 业务核心 |
| `Rating / Charging` 计费/计价 | 根据用量、时长、数量、价格计算费用 | Billing 支撑域 |
| `Bill` 账单 | 分项费用、优惠、税、押金抵扣、应付金额 | Billing 支撑域 |
| `Amount Due` 应付金额 | 账单应收减去已收/抵扣后的金额 | Billing 支撑域 |
| `Checkout` 结账 | 前台结账流程，调用 Billing/Payment | 边缘/应用编排 |
| `Payment` 支付 | 资金支付/退款 | Payment 支撑域 |
| `Settlement` 结算 | 商户/OTA/渠道分账与对账 | Settlement 支撑域 |
| `Invoice` 发票 | 税务票据 | 财务/税务域，MVP 不接入 |

不要把 `Checkout` 当成领域服务，也不要把 `Bill` 叫 `Invoice`。

## 2. 为什么可以公共化

各业务最后都要算：

```text
分项费用
优惠/折扣
税/服务费
押金抵扣
已收金额
应付金额
找零/退款
```

这些是**计算与账务不变量**，可以共用。

但“什么算一项费用”是业务规则：

```text
酒店：房晚、房价计划、迷你吧、损坏赔偿、押金
KTV：房费、服务费、商品、超时、套餐
饭店：菜品、服务费、开台费、折扣
健身：课程、私教、商品
```

业务核心负责产生计费项；Billing 负责算账，不拥有业务价格规则。

## 3. 计算模型

```text
Bill
  billNo / tenantId / storeId / sourceType / sourceId
  currency / status / version
  subtotalMinor / discountMinor / taxMinor / serviceChargeMinor
  depositAppliedMinor / paidMinor / amountDueMinor

BillLine
  billId / lineType / title / quantity / unitPriceMinor
  amountMinor / taxMinor / sourceRef

DiscountLine
  billId / promotionId / type / amountMinor / allocation

PaymentRef
  billId / paymentId / amountMinor / status
```

应付金额：

```text
amountDue =
  sum(line.amount)
  - discount
  + tax
  + serviceCharge
  - depositApplied
  - paid
```

所有金额用最小货币单位 + 币种，禁止浮点。

## 4. 计算流程

```text
业务核心
  -> 产生 ChargeRequest / BillLine（业务计费项、价格快照）
Billing
  -> 计算小计、优惠、税、服务费、押金抵扣
  -> 生成 Bill / AmountDue
Payment
  -> 收款/退款
Billing
  -> 回写 paid / amountDue
Checkout（BFF）
  -> 编排 Billing + Payment + 押金
Settlement
  -> 商户/渠道分账、对账
```

关键约束：

- Billing 不读酒店房态、KTV 会话、饭店菜单，只接收业务行项；
- 业务核心必须传价格快照/来源单号，Billing 不反查业务库；
- 同一来源单号 + 幂等键只生成一张账单；
- 账单生成后可重算，但已结算账单不可改，只能冲正/调整；
- 所有计算可追溯：行项、优惠、税率、舍入、操作人。

## 5. 与业务核心的边界

| 业务 | 业务核心负责 | Billing 负责 |
| --- | --- | --- |
| 酒店 | 房晚、房价计划、取消政策、押金策略 | 账单汇总、税、优惠、押金抵扣、应付 |
| KTV | 房费、服务费、套餐、超时规则 | 分项汇总、折扣、应付、支付回写 |
| 饭店 | 菜品、服务费、折扣 | 汇总、税、应付 |
| 健身 | 课程/私教、商品 | 汇总、优惠、应付 |

## 6. 专业命名

推荐：

```text
Billing / Charging Domain     计费域
Rating Engine                 计价引擎
Bill                          账单
Bill Line                     账单行
Amount Due                    应付金额
Checkout                      结账流程
Settlement                    结算
Invoice                       发票
```

不推荐：

```text
Checkout Service              把流程当领域
Payable Service               语义过窄且与应付会计混淆
Billing Center                中台化空词
Invoice Service               发票 ≠ 账单
```

## 7. 演进

### H1：模块化

- Billing 作为 order/hotel 内的计算模块，不新建服务；
- 先服务酒店，复用 Payment；
- 账单模型、行项、幂等、舍入先固化。

### H2：多业务复用

触发条件：KTV/饭店/健身也依赖同一套计算。

```text
抽 platform-billing-service
Billing 独立表/契约
业务核心通过 ACL 提交 ChargeRequest
```

### H3：结算与财务

```text
Settlement / 对账
发票/税务
多币种/多税率
```

## 8. 数据隔离与权限

- 账单带 `tenant_id + store_id`；
- 业务核心只能查自己门店账单；
- 后端从会话解析上下文；
- 跨门店/租户 403/404；
- 账单调整/冲正需要权限与审计。

## 9. 反模式

- 把酒店房价规则写进 Billing；
- 每个业务各写一套金额计算；
- 用 `double` 算钱；
- 账单已结算还能改；
- 把 Checkout 当领域服务；
- 把 Bill 当 Invoice。

## 10. 相关文档

- [业务域版图](../DOMAIN_MAP.md)
- [公共支撑域蓝图](../PLATFORM_DOMAINS.md)
- [订单平台域](../order/README.md)
- [酒店实现方案](../hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [币种规范](../../80-development/standards/24_CURRENCY_CONVENTIONS.md)
