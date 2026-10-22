# 库存平台域

[中文](README.md) | [English](README.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 定位：公共支撑域 / 平台域，不是酒店私有域，也不是某个业务域的共享内核。  
> 结论：库存是上游供应方；酒店、KTV、按摩、饭店、健身是下游消费方。

## 1. 领域分类

用 DDD 的子域分类，而不是“所有业务共用一张表”：

| 类型 | 例子 | 说明 |
| --- | --- | --- |
| 核心域（Core） | 酒店预订、KTV 运营、饭店翻台 | 形成业务差异化的规则 |
| 支撑域（Supporting） | 库存、支付、客户、审计 | 业务需要，但不是差异化核心 |
| 通用域（Generic） | 身份认证、消息、文件 | 可采购/复用 |

库存属于**支撑域**，并进一步分为两个子域：

```text
Stock       采销库存：SKU、仓库、余额、出入库、盘点、成本、批次
Capacity    容量库存：资源/时段/日期容量、保留、占用、释放
```

“中台”可以作为组织叫法，但架构上应称为**公共支撑平台**，避免把业务规则塞进平台。

## 2. 上下文映射

```text
Procurement（采购）
        │ 收货/入库
        ▼
Inventory Platform（库存平台）
  ├─ Stock Context
  └─ Capacity Context
        ▲
        │ 预订/占库/释放/库存查询
        │
┌───────┴────────┬──────────────┬──────────────┐
│ Hotel Context  │ KTV Context  │ Massage /    │
│                │              │ Restaurant / │
│                │              │ Gym Context  │
└────────────────┴──────────────┴──────────────┘
```

关系：

- **Inventory 是上游（upstream/supplier）**；
- 酒店/KTV 等是下游（downstream/customer）；
- 下游通过 **Published Language + Anti-Corruption Layer** 调用库存；
- Inventory 不依赖、不导入任何酒店/KTV 业务类；
- 不允许业务域直接读写 `inv_*` 表。

这不是 Shared Kernel。Shared Kernel 是两个上下文共享同一模型、强耦合；这里共享的是**契约和平台能力**，不是业务模型。

## 3. 职责边界

### 库存平台拥有

```text
Stock
  inv_stock_item
  inv_stock_warehouse / location
  inv_stock_balance
  inv_stock_movement
  inv_stock_reservation
  inv_stock_batch

Capacity
  inv_capacity_unit
  inv_capacity_slot
  inv_capacity_hold
  inv_capacity_policy（执行，不定义业务规则）
```

### 业务域拥有

| 业务域 | 自有模型 | 依赖库存做什么 |
| --- | --- | --- |
| 酒店 | Hotel、RoomType、RatePlan、HotelBooking、StayDeposit | 房型每日容量、hold、释放；迷你吧/耗材走 Stock |
| KTV | 包厢、会话、计价、服务人员 | 包厢时段容量、占用、释放；商品走 Stock |
| 按摩 | 技师/房间、服务项目、预约 | 技师/房间时段容量 |
| 饭店 | 桌台/区域、菜单、翻台 | 桌台时段容量；食材走 Stock |
| 健身 | 课程/器械/场地、会员 | 课程/场地时段容量；商品走 Stock |
| 采购 | 供应商、采购单、收货 | 收货写入 Stock |

关键：**库存平台不知道“酒店”“包厢”“桌台”这些业务概念**，只知道：

```text
tenantId / storeId
inventoryUnitId
businessType
granularity
slot
quantity
```

业务含义由业务域解释。

## 4. 策略与执行分离

库存平台提供执行引擎，业务域提供策略参数：

```text
CapacityPolicy
  granularity        DAY / SLOT
  slotDuration       30m / 60m / 1d
  rangeMode          SINGLE_SLOT / DATE_RANGE
  capacitySource     FIXED / CALENDAR
  allowOverbook      false
  releaseRule        ON_EXPIRE / ON_CANCEL / MANUAL
  allocationRule     ROOM_TYPE_THEN_ROOM / RESOURCE_ONLY
```

| 业务 | 策略 |
| --- | --- |
| 酒店 | DAY、多晚、按房型、入住分配房间 |
| KTV | SLOT、按包厢、开台/结台 |
| 按摩 | SLOT、按技师/房间、服务时长 |
| 饭店 | SLOT、按桌台、翻台 |
| 健身 | SLOT、按课程/场地、容量 |

平台执行统一不变量：

```text
不超卖
hold 幂等
过期释放
版本冲突可检测
所有操作可审计
租户/门店隔离
```

业务域只负责“怎么解释库存”，不重复实现这些不变量。

## 5. 依赖与集成规则

### 允许

```text
Hotel -> Inventory（HTTP/内部 API）
KTV -> Inventory（适配层）
Procurement -> Inventory（收货/入库）
Inventory -> Audit（事件）
Inventory -> MQ（库存事件）
```

### 禁止

```text
Inventory -> Hotel / KTV（反向依赖）
Hotel / KTV 直接读写 inv_* 表
Inventory 表里出现 hotel_id / room_type_id / ktv_room_id 等业务列
业务域复制一套 hold/超卖逻辑
```

### 契约

```text
命令：reserve / commit / release / adjust / receive / issue / transfer
查询：availability / balance / movements
事件：inventory.capacity.held / released / stock.low
```

所有写接口要求：

```text
tenantId + storeId
Idempotency-Key
业务单据号
版本号/预期版本
```

## 6. 数据隔离与权限

- 所有库存数据带 `tenant_id + store_id`；
- 库存平台不提供“跨门店通用查询”给业务页面；
- 集团/租户级只读聚合是独立读模型 + 独立权限；
- 后端从会话解析 tenant/store，不信任前端传值；
- 前端路由/菜单/按钮按 `inventory.*` + 业务权限隐藏，但只做体验；
- 业务域只能看到自己门店的库存。

## 7. 演进路径

```text
阶段 1：库存模块在 platform-resource-service
  stock/ + capacity/ + policy + projection

阶段 2：抽成 platform-inventory-service
  当酒店、KTV、按摩、饭店、健身、采销同时依赖时

阶段 3：KTV 从 res_occupation 适配到 capacity
  不强制迁移，先适配层
```

不在阶段 1 新建独立服务，是为了避免 MVP 分布式事务；但领域边界从一开始就是公共平台，不是酒店私有模块。

## 8. 反模式

- 把库存做成一张万能表；
- 库存平台导入酒店/KTV 业务类；
- 业务域直接写 `inv_*` 表；
- 每个业务复制一套超卖/hold 逻辑；
- 用“中台”名义把所有业务规则塞进库存；
- 只做前端权限，不做后端门店校验。

## 9. 相关文档

- [ADR-0010：库存作为公共支撑域（stock + capacity）](../../20-architecture/adr/0010-shared-inventory-kernel.md)
- [ADR-0011：库存上下文映射与依赖规则](../../20-architecture/adr/0011-inventory-context-map.md)
- [酒店实现方案](../hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [资源服务](../../50-services/platform/platform-resource-service/README.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
