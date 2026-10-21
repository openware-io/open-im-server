# 酒店业务

[中文](README.md) | [English](README.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 范围：酒店预订、房型/房价、库存日历、订单支付、入住/退房、取消与账务。  
> 说明：本目录是酒店业务的设计基线，不是已实现契约；最终以代码、迁移和 OpenAPI 为准。

## 文档

| 文档 | 说明 |
| --- | --- |
| [实现方案](HOTEL_IMPLEMENTATION_PLAN.md) | 业务范围、领域边界、模型、流程、API、库存并发与迭代计划 |
| [决策与待确认问题](HOTEL_DECISIONS.md) | 已建议的默认决策、必须产品/业务拍板的问题 |

## 现状

- 原型：`open-chat-app/web/miniservice/a380` 的酒店 H5 演示；
- 现有能力：酒店列表/详情、房型套餐、入住日期、到店时间、房间数、预订人信息、mock 订单；
- 尚未覆盖：离店日期、住客人数、实时库存、价格明细、支付、取消政策、入住/退房、发票与公安登记。

## 相关

- [业务文档目录](../README.md)
- [订单服务](../../50-services/platform/platform-order-service/README.md)
- [资源服务](../../50-services/platform/platform-resource-service/README.md)
- [支付服务](../../50-services/common/common-payment-service/README.md)
- [订单/预约状态机](../../20-architecture/diagrams/08-order-reservation-state.md)
