# 酒店业务

[中文](README.md) | [English](README.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 范围：酒店预订、房型/房价、库存日历、订单支付、入住/退房、取消与账务。  
> 说明：本目录是酒店业务的设计基线，不是已实现契约；最终以代码、迁移和 OpenAPI 为准。

## 文档

| 文档 | 说明 |
| --- | --- |
| [实现方案](HOTEL_IMPLEMENTATION_PLAN.md) | 业务范围、领域边界、模型、流程、API、库存并发与迭代计划 |
| [OTA / 渠道接入规范](HOTEL_OTA_INTEGRATION.md) | 对外 ARI、订单、取消、鉴权、幂等与沙箱契约 |
| [方案规范审查](HOTEL_DESIGN_REVIEW.md) | 编码前的规范符合性结论与必补项 |
| [决策与待确认问题](HOTEL_DECISIONS.md) | 已确认决策、术语和剩余问题 |

## 现状

- C 端原型：`open-chat-app/lib/screens/mock_venue_flow_screens.dart`（服务中心 → 酒店 → 列表/详情/房型/预订；已补离店日期、客房数、住客人数）；
- B 端原型：`open-saas-admin/src/views/tenant/hotel.vue`，路由 `/admin/tenant/hotel`（开发环境侧栏可见）；
- B 端覆盖：经营概览、酒店资料、接入模式/销售入口、支付策略、房型房价、库存日历、预订订单、入住/退房；
- 数据：全部为本地 mock，不调用后端；后续接入时替换数据源，不重建页面；
- 尚未覆盖：真实库存、支付、取消退款、发票与公安登记、后端 BFF/API。

## 相关

- [业务文档目录](../README.md)
- [库存平台域](../inventory/README.md)
- [订单平台域](../order/README.md)
- [订单服务](../../50-services/platform/platform-order-service/README.md)
- [资源服务](../../50-services/platform/platform-resource-service/README.md)
- [支付服务](../../50-services/common/common-payment-service/README.md)
- [订单/预约状态机](../../20-architecture/diagrams/08-order-reservation-state.md)
