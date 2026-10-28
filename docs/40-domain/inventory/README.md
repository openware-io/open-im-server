# 库存平台域（Stock）

[中文](README.md) | [English](README.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）
> 定位：**采销库存**公共支撑域，不是容量库存平台。
> 关键边界：Stock 是平台能力；Capacity 是各业务核心域，不能共享表、共享模型、共享服务。

## 1. 领域分类

| 类型 | 例子 | 说明 |
| --- | --- | --- |
| 核心域（Core） | 酒店预订/房态、KTV 包厢运营、饭店翻台、按摩排班、健身课表 | 业务差异化规则，各自实现 |
| 支撑域（Supporting） | 采销库存、支付、客户、审计 | 公共能力，服务多个业务 |
| 通用域（Generic） | 身份、消息、文件 | 可采购/复用 |

本文件只描述 **Stock（采销库存）**。容量/可用量属于业务核心域，见各业务文档。

## 2. 为什么容量不能放进公共库存

酒店房间不足，不能通过采购补房间；KTV 包厢、饭店桌台、健身课程容量也一样。

```text
酒店房间库存 = 酒店核心经营能力（房价、房态、超卖、取消、入住）
KTV 包厢容量 = KTV 核心运营能力（时段、开台、结台、服务）
饭店桌台容量 = 饭店核心运营能力（餐段、翻台、预订）
按摩/健身容量 = 各自核心排班/课表能力
```

它们只是“都有可用量”这一**模式相似**，不是同一个领域模型。

因此禁止：

```text
inv_capacity_* 公共表
跨业务共享容量服务
库存平台反向依赖酒店/KTV
```

允许：

```text
共享技术库：日历计算、hold 状态机、乐观锁、幂等、测试夹具
共享 UI 组件：日期选择、容量单元格、状态标签
借鉴同一套设计模式
```

## 3. Stock 的职责

```text
SKU / 商品
仓库 / 门店库位
库存余额
入库 / 出库 / 盘点 / 调拨
库存预留 / 释放
成本
批次 / 效期
安全库存
库存流水
```

适用：

```text
酒店迷你吧、耗材
KTV 商品、酒水
饭店食材、耗材
健身商品、耗材
零售商品
```

## 4. 上下文关系

```text
Procurement（采购）
        │ 收货/入库
        ▼
Stock Platform（采销库存）
        ▲
        │ 商品/耗材库存查询、预留、出入库
        │
Hotel / KTV / Restaurant / Gym / Retail
```

- Stock 是上游供应方；
- 业务域是下游消费方；
- Stock 不知道酒店、包厢、桌台；
- 业务域不直接写 `inv_stock_*` 表；
- 业务域通过版本化契约 + ACL 调用。

## 5. 各业务容量归属

| 业务 | 容量核心模型 | 归属 |
| --- | --- | --- |
| 酒店 | 房型每日库存、多晚 hold、房态、入住分配 | 酒店核心模块 |
| KTV | 包厢时段占用、会话、开台/结台 | KTV 核心模块 |
| 按摩 | 技师/房间时段、服务时长 | 按摩核心模块 |
| 饭店 | 桌台/区域时段、翻台 | 饭店核心模块 |
| 健身 | 课程/场地时段、容量、候补 | 健身核心模块 |

业务核心可以复用技术组件，但不能共享容量表。

## 6. 依赖规则

允许：

```text
Hotel / KTV / Restaurant / Gym -> Stock Platform
Procurement -> Stock Platform
Stock Platform -> Audit / MQ
```

禁止：

```text
Stock Platform -> Hotel / KTV
Hotel / KTV -> 另一个业务核心
业务域直接读写 inv_stock_* 表
Stock 表出现 hotel_id / room_type_id / ktv_room_id
```

## 7. 数据隔离与权限

- 所有 Stock 数据带 `tenant_id + store_id`；
- 业务域只能访问自己门店的库存；
- 后端从会话解析 tenant/store，不信任前端传值；
- 前端隐藏路由/菜单/按钮只是体验，不是安全边界；
- 跨门店/跨租户访问返回 403/404；
- 集团只读汇总走独立读模型和权限。

## 8. 演进

```text
阶段 1：Stock 模块（platform-resource-service 内或独立模块）
阶段 2：抽成 platform-inventory-service
阶段 3：各业务容量继续在自己的核心模块演进，不迁入 Stock
```

## 9. 相关文档

- [ADR-0010：采销库存作为公共支撑域](../../20-architecture/adr/0010-stock-supporting-domain.md)
- [ADR-0011：库存上下文映射与依赖规则](../../20-architecture/adr/0011-inventory-context-map.md)
- [ADR-0008：酒店域边界](../../20-architecture/adr/0008-hotel-domain-boundary.md)
- [酒店实现方案](../hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
