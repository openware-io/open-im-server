# ADR 0023：C 端消费上下文、跨门店资源归属与用户订单读模型

## Status / 状态

**proposed（提案中，2026-10-30）**。本 ADR 收口 C 端消费应用的上下文、跨门店资源归属和用户订单读模型边界；不改变 B 端按门店运营的写模型。

## Context / 背景

C 端消费应用（如 `saas-c`）当前以“应用安装门店”作为消费者上下文锚点。多业态租户（同一租户下 KTV 门店 100、酒店门店 102）面对三个具体问题：

1. C 用户从 KTV 门店安装的消费应用进入酒店服务，酒店报价/下单需要酒店门店 102 的作用域；
2. `/me/orders` 按 `ord_order.customer_id` 查询，但酒店下单如果发生在会员档案懒初始化之前，`customer_id` 为空，订单永远不可见；
3. “我的酒店订单”按当前安装门店过滤，酒店门店 102 的订单在 KTV 上下文下返回空；
4. ADR-0009 已提出 C 端统一订单索引 `ord_user_order_index`，但当前尚未落地，导致每个业务各自拼“我的”查询，状态语义也不一致。

这不是一个 UI 过滤器问题，而是**消费身份作用域**与**业务资源作用域**没有分离。

## Decision / 决策

### 1. 消费身份与安装锚点分离

- C 端 `CUSTOMER membership`、会员档案、消费者权限属于**租户级**；
- consumer app installation 只提供租户锚点和默认上下文，不等于该用户只能操作安装门店；
- C 端读模型按 `tenantId + accountId/customerId` 聚合，不再把安装门店作为用户订单的硬过滤条件。

### 2. 资源权威门店

任何业务写操作的门店作用域必须来自资源权威数据，而不是客户端声明的门店：

- 酒店：`htl_room_type.store_id → htl_hotel.store_id`；
- KTV：`res_resource.store_id` / 房型门店；
- 服务层必须校验资源所属租户等于当前租户；禁止信任 URL/请求体中的 `storeId` 直接写库。

过渡实现：在酒店 C 端用例中按房型的 `storeId` 构造受控门店作用域；后续抽象为统一的 `ResourceAuthorityResolver`，禁止各服务各自拼装。

### 3. 用户订单读模型

落地 ADR-0009 的 C 端统一订单读模型：

```text
ord_user_order_index
  platform_user_id
  tenant_id
  store_id
  order_type / order_id / order_no
  display_status
  amount_minor / currency
  created_at
```

在索引落地前，允许各域按以下约束提供过渡查询：

```text
tenantId + accountId/customerId
```

不得用 C 应用安装门店过滤“我的订单”。

### 4. 统一用户可见状态

C 端只消费**用户可见状态投影**：

```text
PENDING_CONFIRM / PENDING_PAYMENT / CONFIRMED / CHECKED_IN / COMPLETED / CANCELLED
```

不得把 `ord_order.trade_status=DRAFT` 直接作为“我的订单”的最终展示状态；酒店预订状态与订单交易状态由读模型投影统一。`display_status` 的枚举纳入 OpenAPI 契约和前端契约测试。

### 5. C 订单数据不变量

由 C 端创建的酒店订单必须满足：

```text
ord_order.customer_id IS NOT NULL
ord_order.tenant_id = htl_booking.tenant_id
htl_booking.order_id 唯一
```

创建前必须在同一事务/同一服务边界内完成会员解析；无法解析时返回 `MEMBER_INITIALIZATION_REQUIRED`，不允许生成不可见订单。历史空值通过幂等数据迁移回填，不能只修手工测试数据。

### 6. 列表与确认契约

- B/C 列表统一 cursor/limit 或 page/size，禁止无限全量返回；
- B 端酒店确认、取消必须带 `expectedVersion` 或幂等键；
- B 端按手机号归属 `bookedByAccountId` 必须校验租户、账号状态、隐私授权并写审计；
- C/B 端列表默认限制日期范围。

### 7. 跨端测试基线

CI 必须覆盖：

```text
新 C 账号首次 OAuth
→ C 酒店/KTV 下单
→ C 我的预约/酒店订单/我的消费可见
→ B 门店经理列表可见并响应
→ SaaS 后台可见
```

验收断言必须包含“订单可见性”，不能只断言 `201/200`。

## Consequences / 后果

正向：

- 解决多业态租户 C 端“下单成功但我的看不到”；
- 用户订单查询与业务写作用域解耦，减少每个业务重复实现；
- 状态、分页、幂等有统一契约；
- 历史数据修复路径明确。

代价与约束：

- 需要落地 `ord_user_order_index` 投影和对账，短期存在双读；
- 酒店/KTV 等服务需要统一资源权威解析；
- B/C 前端需要适配分页和 `displayStatus`；
- 数据回填和状态投影需要迁移和监控。

## Alternatives / 备选方案

1. **继续按 C 应用安装门店过滤用户订单**：拒绝，必然在多业态租户再次出现不可见订单。
2. **让 C 端切换安装门店后再查**：拒绝，安装门店是部署锚点，不是用户身份。
3. **直接把 `trade_status` 当用户状态**：拒绝，订单交易状态与酒店预订状态语义不同。
4. **仅前端合并酒店/订单接口**：拒绝，无法解决状态、分页、跨端一致性和历史数据。

## References / 参考

- ADR-0009：C 端统一订单视图
- ADR-0012：订单作为共享交易内核
- ADR-0022：租户工作区、上下文与门店数据范围
- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [订单服务](../../50-services/platform/platform-order-service/README.md)
- [身份接入模型](../../40-domain/identity/IDENTITY_ACCESS_MODEL.md)
