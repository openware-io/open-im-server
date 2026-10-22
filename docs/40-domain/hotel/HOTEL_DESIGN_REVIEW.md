# 酒店方案规范审查

[中文](HOTEL_DESIGN_REVIEW.md) | [English](HOTEL_DESIGN_REVIEW.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 目的：在编码前按现有工程标准审查酒店方案，避免“先写代码、后补规范”。  
> 结论：方案方向可行；有 5 项必须在 S1 前补齐，否则不应进入实现。

## 1. 审查范围

- 文档规范、双语、状态、可达性；
- 架构边界、ADR、依赖方向；
- API 契约、版本、幂等、错误码；
- 数据命名、迁移、金额、租户隔离；
- 安全、隐私、审计；
- 可观测性、测试、发布；
- 门禁与文档同步。

## 2. 审查结论

| 维度 | 结论 | 说明 |
| --- | --- | --- |
| 文档规范 | ⚠ 有条件通过 | 酒店文档已双语、状态化、可索引；OTA 规范仍是 draft |
| 架构边界 | ⚠ ADR-0008 proposed | 推荐 resource + order 模块化；ADR 已建，待评审为 accepted |
| API 契约 | ⚠ 部分完成 | `hotel-channel-v1.yaml` 已建；`hotel-booking-v1.yaml` 待建 |
| 数据模型 | ⚠ 待评审 | 表名/唯一键/迁移方案未冻结 |
| 安全 | ⚠ 待评审 | PII、租户隔离、OTA 凭据已提要求，未形成检查项 |
| 可观测性 | ⚠ 待评审 | 指标名已提，未接入标准 |
| 测试 | ❌ 未完成 | 无并发/支付/OTA 契约测试用例 |
| 门禁 | ⚠ 待接入 | 需明确 G18/G24/G25/G27/G32/G43/G45/G52 的适用点 |
| 原型 | ⚠ 部分完成 | C/B Web 已有；移动前台未建 |

**结论：可以做产品讨论和原型，不应进入后端编码；先补 5 项。**

## 3. 必须补齐的 5 项

### R1. 架构决策记录（ADR）

酒店边界是架构决策，不能只写在方案文档里。已新增 ADR-0008（proposed），需评审：

```text
docs/20-architecture/adr/0008-hotel-domain-boundary.md
```

至少回答：

- 为什么 MVP 不新建 `platform-hotel-service`；
- 酒店库存/房价归 `platform-resource-service` 的边界；
- 酒店预订单归 `platform-order-service` 的边界；
- 什么条件下拆出独立服务。

### R2. OpenAPI 契约

先有契约，再有 Controller：

```text
docs/30-reference/contracts/openapi/hotel-booking-v1.yaml
docs/30-reference/contracts/openapi/hotel-channel-v1.yaml
```

要求：

- 所有 C 端/后台/内部接口有 schema、错误码、幂等头；
- OTA 对外契约单独版本；
- 契约测试纳入门禁或 CI。

### R3. 数据模型与迁移评审

- 表前缀：`res_*` / `ord_*`，不跨域建表；
- 唯一键：库存 `(tenant_id, room_type_id, stay_date)`；外部订单 `(channel_id, external_ref)`；
- 金额：`*_minor` + `currency`，禁止浮点；
- Flyway 命名/不可回改遵守 G27/G28；
- 迁移前先出 ER 图和表清单，评审后再写 SQL。

### R4. 安全与租户隔离检查项

- 所有查询强制 `tenantId`/`storeId`；
- 住客手机号、证件号加密/脱敏；
- OTA 凭据只存 Secret 引用；
- 支付不落卡号/CVV；
- 审计覆盖酒店、房价、库存、订单、入住/退房、退款；
- 跨租户访问测试必须包含 403/404 不泄露。

### R5. 测试与可观测性

- 并发抢最后一间房：成功数 ≤ 库存；
- 支付回调重复/乱序：不重复确认、不重复扣库存；
- 取消/退款：库存与金额正确；
- OTA 契约：重复下单、库存不足、签名失败、乱序 ARI；
- 指标：搜索延迟、下单/确认/取消、库存冲突、hold 过期、支付回调延迟、超卖数（必须 0）。

## 4. 已符合的规范

- 文档双语、状态标记、语言切换；
- 文档可从 `docs/README` 到达；
- 金额最小单位、币种、租户/门店隔离方向；
- 订单/库存/支付边界与现有服务职责一致；
- MVP 范围、OTA 契约、发票/公安排除已明确；
- C/B Web 原型已进入代码，不是独立原型项目。

## 5. 与门禁的对应

| 门禁 | 适用点 |
| --- | --- |
| G18 service-api-boundaries | 酒店 API 不越出服务边界 |
| G24 port-contract | 库存/订单仓储端口 |
| G25 po-containment | PO 不越出持久化层 |
| G27/G28 Flyway | 迁移命名、不可回改 |
| G32 tenant-sql | 租户 SQL 隔离 |
| G43 service-layout | 新模块目录固定模板 |
| G45 controller-mapping-integrity | 路由不重复 |
| G52 docs-impact | API/迁移/门禁/部署变更同步文档 |

## 6. S1 前检查清单

```text
[x] ADR-0008 已提交（proposed，待 accepted）
[ ] hotel-booking-v1.yaml 已评审
[x] hotel-channel-v1.yaml 已创建（待评审）
[ ] 表清单 + ER 图已评审
[ ] 安全/租户隔离检查项已确认
[ ] 并发/支付/OTA 测试用例已列出
[ ] 移动前台原型已建（参考 KTV B 端）
[ ] invoice/公安登记明确为“不接入，仅预留”
```

## 7. 相关文档

- [实现方案](HOTEL_IMPLEMENTATION_PLAN.md)
- [OTA 接入规范](HOTEL_OTA_INTEGRATION.md)
- [决策](HOTEL_DECISIONS.md)
- [工程标准版图](../../80-development/standards/STANDARDS_MAP.md)
- [门禁目录](../../80-development/governance/GATE_CATALOG.md)

## 8. 2026-10-22 增量审查

| 增量 | 结论 | 动作 |
| --- | --- | --- |
| C 端订单跨集团/门店 | 平台级能力，不是酒店私有接口 | ADR-0009 `proposed`；补 `ord_user_order_index` 和回填/对账 |
| OTA 首发不接、沙箱不做 | 只做规范与内部渠道模型 | 契约先行，真实适配器后置 |
| 会员/优惠券 | 不并入酒店 MVP | 积分赚取+抵扣；会员/优惠券单独立项 |
| 币种 | 默认 USD，支持 USD/CNY | `HotelPolicy.currency`，门店可配置 |
| 移动前台 | 纳入 MVP | 参考 KTV B 端，原型待补 |
| 发票/公安 | 不接入 | 字段预留，不进入 MVP 验收 |