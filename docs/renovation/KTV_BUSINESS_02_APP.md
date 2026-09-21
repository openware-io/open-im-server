# KTV 业务 B 端 App 实施方案

> **变更记录（v1）**
> - 首发创建：B 端门店运营 App 的 KTV 页面/交互细化到可派工，覆盖开台/计时/加项/点服务人员/结台/组合收款/交班/A380币充值/审批，对齐 [KTV_BUSINESS_01_SERVICE](KTV_BUSINESS_01_SERVICE.md) 状态机、API 与权限码。

## 0. 方案集声明

| 项目 | 内容 |
| --- | --- |
| 方案集 | `KTV_BUSINESS` |
| 顺序号 | `02` |
| 实施边界 | `APP`（B 端门店运营 App） |
| 前置方案 | [KTV_BUSINESS_01_SERVICE](KTV_BUSINESS_01_SERVICE.md)、[SAAS_PLATFORM_03_APP](SAAS_PLATFORM_03_APP.md)、[SAAS_PLATFORM_08_APP](SAAS_PLATFORM_08_APP.md) |
| 后置方案 | [KTV_BUSINESS_03_ADMIN](KTV_BUSINESS_03_ADMIN.md)、[KTV_BUSINESS_04_TEST](KTV_BUSINESS_04_TEST.md) |
| 目标工程 | `D:\projects\cnb\gv_chat_app`，B 端独立入口/flavor（沿用 SAAS_PLATFORM_03 §14 工程落地） |

## 1. 范围与原则

本文把 KTV 的 B 端 App 页面、交互、调用 API、权限与异常态细化到可派工。金额、状态、权限一律以后端为准，B 端不自行计算金额、不改资源/资金权威状态。

- 写操作提交中禁止重复点击；超时不自动重放资金请求，用同幂等键查询结果。
- 资源看板/订单工作台以 `allowedActions` 决定按钮；无权限与状态不允许不展示伪可操作入口。
- 收银/交班/A380币充值不承诺离线；弱网只允许查看缓存，资金写按钮禁用。

## 2. 页面清单与角色

| 路由 | 页面 | 主要角色 | 权限（首要是） |
| --- | --- | --- | --- |
| `/business/ktv/resources` | 包厢+服务人员看板 | 店长/收银/前台/服务员 | `reservation.view` |
| `/business/ktv/orders/new` | 快速开台 | 店长/收银 | `order.create`+`ktv.session.open` |
| `/business/ktv/sessions/:id` | 开台/计时/加项/点服务人员/结台 | 店长/收银/服务员(部分) | `ktv.session.open/close`、`order.add_item`、`ktv.server.order` |
| `/business/ktv/orders/:id` | 订单工作台 | 店长/收银/服务员 | `order.settle`、`payment.collect` |
| `/business/ktv/cashier` | 组合收款 | 店长/收银 | `payment.collect` |
| `/business/ktv/wallets` | A380币充值/查询 | 店长/收银/财务 | `wallet.recharge`/`wallet.view` |
| `/business/ktv/shifts` | 开班/交班 | 店长/收银 | `shift.open`/`shift.close` |
| `/business/ktv/approvals` | 退款/作废/暂停修正审批 | 店长/财务 | `payment.refund.approve`、`order.void`、`ktv.session.correct_pause` |

## 3. 页面详细规格

### 3.1 包厢 + 服务人员看板

| 项 | 规格 |
| --- | --- |
| 数据源 | `GET /api/v1/business/resources/board`（聚合，包厢 `KTV_ROOM` + 服务人员 `KTV_SERVER`） |
| 包厢状态 | 可用 / 已预留 / 使用中 / 不可用（§01 3.2 投影） |
| 服务人员状态 | 空闲 / 服务中（`IN_USE`）/ 停用 |
| 交互 | 点击包厢：可用→快速开台；使用中→进入会话；已预留→查看预订/到店/取消 |
| 刷新 | 下拉刷新 + 前台 15~30 秒定时；`stateVersion` 落后回源 |

### 3.2 快速开台

| 项 | 规格 |
| --- | --- |
| 步骤 | 选包厢 → 选客户（可跳过/建待认领）→ 加项（可选）→ 提交 |
| API | `POST /api/v1/business/orders`（`businessType=KTV, storeId, items[], reservationId?`） |
| 成功 | 返回订单号 + 资源状态 + `allowedActions`；跳会话页。订单号格式 `O<yyyyMMdd><当日序号>`（如 `O202609190001`，营业日 + 租户维度当日序号，见 SAAS_PLATFORM_04 §6.1），客户端只做不透明字符串展示 |
| 冲突 | `RESOURCE_OCCUPIED` 保留已选，提示重新选包厢 |

### 3.3 会话页（开台/计时/加项/点服务人员/结台）

| 操作 | API | 前置状态 | 成功反馈 |
| --- | --- | --- | --- |
| 开台 | `POST /ktv/sessions/{id}/open` | `RESERVED` | 显示计时开始、`billing_start_at` |
| 暂停 | `POST /ktv/sessions/{id}/pause` | `OPEN` | 计时暂停，显示累计暂停时长 |
| 恢复 | `POST /ktv/sessions/{id}/resume` | `PAUSED` | 计时恢复 |
| 加项 | `POST /orders/{id}/items` | `SERVING` | 明细追加，价格服务端返回 |
| 点服务人员 | `POST /orders/{id}/servers` | `SERVING` | 服务人员卡片进入计时，占用 `IN_USE` |
| 结束服务 | `POST /ktv/servers/{id}/end` | 服务人员 `SERVING` | 服务人员费固化，释放 |
| 挂单 | `POST /orders/{id}/hold` | `SERVING` | 挂单标记，计时继续 |
| 转台 | `POST /orders/{id}/transfer` | `SERVING` | 换包厢，计时继承 |
| 结台 | `POST /ktv/sessions/{id}/close` | `OPEN/PAUSED` | 释放包厢，订单 `WAITING_SETTLEMENT`，跳结算 |

页面顶部固定显示：订单号、公共状态、会话状态、包厢号、客户脱敏、金额与支付摘要（SAAS_PLATFORM_08 §4.5）。

### 3.4 组合收款页

| 项 | 规格 |
| --- | --- |
| 数据源 | `POST /orders/{id}/settle` 得应收；`GET /payments/available-methods` 得可用方式 |
| 支付方式 | 现金（始终）/ 储值（品牌展示名默认「A380币」，显示余额）/ 积分（显示可用积分）/ 线上渠道（未启用不显示） |
| 交互 | 逐笔输入金额，前端校验 ≤ 剩余应收；提交 `POST /orders/{id}/collect` |
| 服务端返回 | `remainingAmount` + `collectedByMethod[]`（现金/A380币/积分已收） |
| 找零 | 现金超额输入时显示找零 `changeAmount`（服务端计算） |
| 异常 | `LEDGER_INSUFFICIENT`（A380币/积分不足）、`PAYMENT_CHANNEL_DISABLED`、`PAYMENT_AMOUNT_MISMATCH` |

### 3.5 订单工作台 / 账单展示

| 项 | 规格 |
| --- | --- |
| 数据源 | `GET /orders/{id}/workspace` + `GET /orders/{id}/bill` |
| 展示 | 分级明细：计时费/加项/服务人员费/优惠逐项/应收合计/已收分项/找零 |
| 一致性 | 只展示服务端账单快照，不本地计算金额 |

### 3.6 A380币充值/查询页

| 动作 | API | 说明 |
| --- | --- | --- |
| 充值 | `POST /admin/wallets/recharge` | 现金/线下转账收储值，录入 `amount/paymentMethod/referenceNo` |
| 余额 | `GET /business/wallets/{customerId}` | `available_amount`/`frozen_amount` |
| 流水 | `GET /business/wallets/{customerId}/ledger` | 分页账本 |

> A380币退还（`wallet.refund`）在 B 端仅财务可见（依赖 `KTV_BUSINESS_03_ADMIN` 或财务角色入口）。
> 页面标题与文案使用租户配置表 `tnt_tenant_config` 的 `wallet_brand_name` 项（默认「A380币」），非硬编码；从租户配置/上下文获取，不写死「A380币」。

### 3.7 交班页

| 项 | 规格 |
| --- | --- |
| 开班 | `POST /shifts/open`（`terminalId, openingCash`） |
| 交班 | `POST /shifts/{id}/close`（`actualCash, remark`）；显示 `expected_cash`/`difference_amount`，差额≠0 必填原因 |

### 3.8 审批页

| 类型 | API | 说明 |
| --- | --- | --- |
| 退款审批 | `POST /admin/refund-requests/{id}/approve|reject` | 显示申请金额/可审批范围；申请人不能自批 |
| 作废 | `POST /admin/orders/{id}/void` | 显示订单摘要/影响，需原因 |
| 暂停修正 | `POST /ktv/sessions/{id}/correct-pause` | 店长/财务；修正 `paused_seconds` + 审计 |

## 4. 角色到动作（对齐 01 §10）

| 角色 | 默认页 | 允许动作 |
| --- | --- | --- |
| 店长 | 工作台/看板 | 全店执行 + 退款/作废审批 + 暂停修正 + A380币充值 + 日结提交 |
| 收银员 | 快速开台/收银/班次 | 开台/计时/加项/点服务人员/组合收款/退款申请/交班/A380币充值 |
| 服务员 | 我的待办/订单 | 自己关联订单加项、结台协助 |
| 前台/预约 | 预订/客户/到店 | 预订创建/确认/到店登记/取消 |
| 财务 | 审批/资金/日结 | 退款/作废复核、线下退款、反结、A380币退还、日结复核、审计 |

## 5. 异常态与幂等

| 场景 | 页面行为 |
| --- | --- |
| 资源冲突 `RESOURCE_OCCUPIED` | 保留已选，提示重选包厢/服务人员 |
| 版本冲突 `ORDER_VERSION_CONFLICT` | 保留草稿，刷新权威详情后重新确认 |
| 支付超时 | 显示处理中，提供「查询结果」，不提供「再次支付」 |
| 网络不可用 | 资金/履约写按钮禁用；查看缓存 |
| 401/上下文过期 | 清 B 端会话回登录/门店选择 |
| 重复提交 | 复用同 `Idempotency-Key` 查询原结果 |

## 6. 交付物清单

- [ ] B 端 flavor/入口、`business-v1` client、安全 Token/上下文存储（沿用 SAAS_PLATFORM_03 §14）。
- [ ] 页面：看板、快速开台、会话页、组合收款、订单工作台/账单、A380币充值/查询、交班、审批。
- [ ] 组件：金额格式化（最小单位+币种字符串）、状态文案（公共状态+会话状态）、`allowedActions` 权限组件、幂等提交组件。
- [ ] 单元/Widget 测试：金额格式化、状态映射、幂等键复用、路由守卫、离线资金按钮禁用。
- [ ] 埋点：版本、租户/门店匿名标识、请求关联 ID；不含手机号/证件/Token/支付敏感信息。

## 7. 变更清单

- [ ] B 端 App 新增上述页面与组件，对齐 01 的 API/错误码/权限码。
- [ ] 与 [KTV_BUSINESS_03_ADMIN](KTV_BUSINESS_03_ADMIN.md) 的价目/服务人员/支付开关配置联动（B 端只读使用配置）。
- [ ] E2E 用例见 [KTV_BUSINESS_04_TEST](KTV_BUSINESS_04_TEST.md)。