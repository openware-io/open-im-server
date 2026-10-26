# 酒店业务

[中文](README.md) | [English](README.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 范围：酒店预订、房型/房价、库存日历、订单支付、入住/退房、取消与账务。  
> 说明：本目录是酒店业务的设计基线，不是已实现契约；最终以代码、迁移和 OpenAPI 为准。

## 文档

| 文档 | 说明 |
| --- | --- |
| [实现方案](HOTEL_IMPLEMENTATION_PLAN.md) | 业务范围、领域边界、模型、流程、API、库存并发与迭代计划 |
| [数据模型](HOTEL_DATA_MODEL.md) | 酒店域表清单、唯一键、库存不变量、状态机、PII 与边界 |
| [预订 API 契约](../../30-reference/contracts/openapi/hotel-booking-v1.yaml) | 酒店主数据、报价、预订、取消、前台动作的 OpenAPI |
| [OTA / 渠道接入规范](HOTEL_OTA_INTEGRATION.md) | 对外 ARI、订单、取消、鉴权、幂等与沙箱契约 |
| [方案规范审查](HOTEL_DESIGN_REVIEW.md) | 编码前的规范符合性结论与必补项 |
| [决策与待确认问题](HOTEL_DECISIONS.md) | 已确认决策、术语和剩余问题 |
| [原型查看指南](HOTEL_PROTOTYPE_GUIDE.md) | C 端、B 端 Web、移动前台的入口与检查项 |

## 现状

- C 端：`open-saas-mobile/c-end/hotel-real.js`，已接真实酒店目录、报价、预订、订单与取消接口；
- B 端前台：`open-saas-admin/src/views/tenant/hotel.vue`，路由 `/admin/tenant/hotel`，已接真实任务、入住选房、退房、续住、换房、押金、账单、退款、夜审、客房清扫和房态；
- B 端主数据：`open-saas-admin/src/views/tenant/hotel-master.vue`，路由 `/admin/tenant/hotel-master`，维护酒店资料、房型、客房、房价计划、每日房价和按日期库存；
- 后端：`platform-order-service` 的 `hotel` 模块提供真实主数据、库存日历、报价/预订/取消/no-show、前台与账单接口；
- 尚未完成：店铺级支付策略（预付/到店付/定金）、押金策略、取消政策、积分抵扣和 OTA 真实渠道；
- 明确不做：发票、公安入住登记、动态收益定价、跨集团 CRS、复杂套餐/宴会/长租、多币种（仅 USD/CNY）、会员等级价/协议价、OTA 真实渠道与沙箱。

## 相关

- [业务文档目录](../README.md)
- [库存平台域](../inventory/README.md)
- [订单平台域](../order/README.md)
- [订单服务](../../50-services/platform/platform-order-service/README.md)
- [资源服务](../../50-services/platform/platform-resource-service/README.md)
- [支付服务](../../50-services/common/common-payment-service/README.md)
- [订单/预约状态机](../../20-architecture/diagrams/08-order-reservation-state.md)
