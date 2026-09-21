# KTV 业务测试与验收方案

> **变更记录（v1）**
> - 首发创建：KTV 的自动化测试、联调与 E2E 验收细化到可派工，映射 [KTV_BUSINESS_01_SERVICE](KTV_BUSINESS_01_SERVICE.md) §15 的 E2E-KTV-01~14。

## 0. 方案集声明

| 项目 | 内容 |
| --- | --- |
| 方案集 | `KTV_BUSINESS` |
| 顺序号 | `04` |
| 实施边界 | `TEST`（自动化 / 联调 / E2E） |
| 前置方案 | [KTV_BUSINESS_01_SERVICE](KTV_BUSINESS_01_SERVICE.md)、[KTV_BUSINESS_02_APP](KTV_BUSINESS_02_APP.md)、[KTV_BUSINESS_03_ADMIN](KTV_BUSINESS_03_ADMIN.md) |
| 工程依据 | [SAAS_PLATFORM_09_SERVICE](SAAS_PLATFORM_09_SERVICE.md) §6 测试映射 |

## 1. 测试分层与覆盖

| 层 | 必测内容 | 归属 |
| --- | --- | --- |
| Domain | 会话/服务人员状态迁移、计时不可倒退、值对象校验 | order 领域单元测试 |
| Application | 幂等、事务边界、Outbox、补偿、权限结果 | order/resource/payment/customer |
| Infra | Flyway、PO 映射、锁查询、缓存失效、渠道回调、MQ 消费幂等 | 各服务 |
| API | 参数校验、上下文、DTO、HTTP 错误码、权限、分页 | 契约测试 |
| E2E | KTV 全链路、双租户隔离、Admin、B App、旧 C 端回归 | 集成环境 |

## 2. 数据准备

- 租户 T1/T2（隔离用例）、门店 S1/S2、包厢 A01/A02、服务人员 SV01、收银员/店长/服务员/前台/财务账号。
- 价目：包厢单价（HOUR）、加项（酒水/食品）、服务人员价目（HOUR）、套餐（3 小时一口价）。
- 计价方案：`free_wait_minutes=0`、`overtime_rate=1.0`、`default_session_minutes=120`、`pause_enabled=true`。
- 客户：A380币余额、会员积分、会员等级；线上渠道默认关闭。

## 3. 单元/集成用例清单

| 编号 | 用例 | 断言 |
| --- | --- | --- |
| UT-01 | 计时费计算 | `D = T_close − T_start − P`；向上取整到分钟 |
| UT-02 | PACKAGE 套餐 | 套餐时长内一口价，超出按标准单价续费 |
| UT-03 | 超时加价 | 超时部分 `× overtime_rate` |
| UT-04 | 暂停不计费 | `paused_seconds` 只增不减，不计费 |
| UT-05 | 服务人员计费 | `unit_price × ceil(duration/unit)` |
| UT-06 | 优惠叠加 | 特价→会员价→满减→券→积分→A380币顺序 |
| UT-07 | 满减门槛含计时费 | `min_amount` 以优惠前原价合计为基数 |
| UT-08 | 组合收款顺序 | 积分→A380币→现金，逐笔 ≤ 剩余应收 |
| UT-09 | 资源并发 | 同包厢并发开台仅一次成功 |
| UT-10 | 幂等 | 重复命令不产生第二笔事实/流水 |
| UT-11 | 账本平衡 | 充值/消费/退还/冲正流水平衡 |
| UT-12 | 线上支付默认关闭 | 未启用返回 `PAYMENT_CHANNEL_DISABLED` |

## 4. E2E 用例（映射 01 §15）

| 编号 | 场景 | 通过标准 |
| --- | --- | --- |
| E2E-KTV-01 | 主闭环 | 开台→计时→加项→点服务人员→结台→组合收款→日结，金额服务端一致 |
| E2E-KTV-02 | 同包厢并发开台 | 仅一次成功，其余 `RESOURCE_OCCUPIED` |
| E2E-KTV-03 | 暂停不计费 | 金额排除暂停时长 |
| E2E-KTV-04 | 超时加价 | 快照固化 `D_over` |
| E2E-KTV-05 | PACKAGE | 套餐内一口价，超出续费 |
| E2E-KTV-06 | 幂等 | 重复命令无第二笔 |
| E2E-KTV-07 | 权限隔离 | 越权 403 |
| E2E-KTV-08 | 退款 | 现金/线下/A380币退还/积分反向 |
| E2E-KTV-09 | 作废 | 审批后 `VOIDED` + 释放 + 冲正 |
| E2E-KTV-10 | 挂单/转台 | 挂单计时继续；转台计时继承 |
| E2E-KTV-11 | 暂停修正 | `correct-pause` + 审计 |
| E2E-KTV-12 | A380币充值/退还 | `RECHARGE`/`REFUND` + 幂等 + 审计 |
| E2E-KTV-13 | 线上支付默认关闭 | `available-methods` 无线上渠道 |
| E2E-KTV-14 | 租户隔离 | 租户 A 不可读租户 B |

## 5. 联调与发布门槛

- [ ] 集成环境契约测试 → 预发布空库 + KTV E2E → Admin 与 B App 联调 → 小范围租户灰度 → 扩大灰度。
- [ ] 每个 APP 编号具备成功/401/403/空态/网络错误/重复提交/状态冲突测试（SAAS_PLATFORM_03 §11）。
- [ ] 资金幂等：同一支付/退款/充值命令重复 10 次只产生一笔事实。
- [ ] 渠道回调乱序/重复/金额不一致不破坏已完成状态。
- [ ] 旧 C 端登录、预约、消息回归不受影响。

## 6. 变更清单

- [ ] 测试数据/夹具脚本、契约测试、E2E 脚本按本方案落地。
- [ ] 监控：租户隔离拒绝率、订单版本冲突率、资源冲突率、支付/充值幂等命中率、Outbox 死信、账本不平告警（对齐 SAAS_PLATFORM_06 §11.2/§12）。
