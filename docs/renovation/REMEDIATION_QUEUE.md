# 整改队列

本队列记录“门禁已绿、但证据/行为仍不完整”的遗留项。规则：

- 每项有编号、现状、为什么现在的门禁不够、验收标准；
- 按“风险 × 廉价”排序，完成一项再进入下一项；
- 完成时同步审计文档与对应门禁，不把“已登记”当“已完成”；
- 不允许用猜测值或永久 allowlist 关闭条目。

| ID | 事项 | 现状 | 为什么不够 | 验收标准 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Q01 | `reserved-identifiers` 的 CAS 证据 | 每条 CAS 已引用 evidence 文件 | 和 MQ 台账一样，可能标了 CAS 但代码没有版本谓词 | 每条 CAS 必须引用证据文件；门禁检查文件存在且含版本谓词/`@Version` | ✅ 完成 |
| Q02 | `MemberRepository.updateAttributes` 允许 `expectedVersion=null` | 已改为必填 `int`，始终 CAS | CAS API 仍留绕过口子 | 生产路径禁止 null；迁移/测试用独立入口并标注 | ✅ 完成 |
| Q03 | `Product` / `InventoryMaterial` 的 CAS 只有 mock 测试 | 已补真实 H2 + Flyway 集成测试 | 没证明真实 SQL 能执行、列集合正确 | 至少一个真实适配器/集成测试覆盖 `WHERE version = ?` 与自增 | ✅ 完成 |
| Q04 | `MktConsent` / `MktCoupon` 的 CAS 无适配器测试 | 已补适配器测试，断言 SQL 谓词与自增 | 0 行冲突路径未覆盖 | 补适配器测试，断言版本谓词与自增 | ✅ 完成 |
| Q05 | `RequestIdTaskDecorator` 未验证 Spring 调度器真的应用 | 只直接调用 decorator | 测试通过不代表 `@Scheduled` 线程带 requestId | Spring 上下文测试：调度任务内 `RequestIdContext` 非空 | 待办 |
| Q06 | `@Async` 线程未传播 `RequestIdContext` | 只覆盖 HTTP/MQ/调度 | `@EnableAsync` 服务异步线程丢关联标识 | 为 async executor 加 TaskDecorator 并测试 | 待办 |
| Q07 | `BUSINESS_VERSION` / `COUNTER` 只登记未改名 | `ClientRelease.version`、`InventoryStock.version` 仍叫 version | B 路线没有真正执行，语义仍可能误导 | 决定改名（DB/API 迁移）或给出不改的 ADR 与禁止并发判定注释 | 待办 |
| Q08 | 币种写穿/快照只有局部测试 | 写穿是 mock 调用，快照只覆盖订单开关台 | 没有真实租户切币种 + 跨服务快照回归 | 集成测试：切币种同事务写穿；订单/支付/退款历史快照不变 | 待办 |
| Q09 | `observability-metrics` 当前 0 指标 | 门禁空绿 | 不能证明指标口径能力 | 第一批指标落地后复审；在规范中注明“尚无指标” | 待办 |
| Q10 | `verify-inventory` 只校验脚本清单 | 不代表 E2E 执行过 | 清单一致 ≠ 真实验收 | CI/发布清单显式调用并保留运行证据 | 待办 |
| Q11 | 43 章层级/真机/清理 | 仍是人工项 | 静态门禁不能替代真实设备与清理核验 | 按 43 章清单产出可复核证据 | 待办 |
| Q12 | 13 章运行期日志采样 | 只有静态日志语句检查 | 运行期可能拼接/间接输出敏感信息 | 抽查真实日志，保留脱敏证据 | 待办 |

## 执行顺序

1. Q01：先把“CAS 登记”变成有证据的检查；
2. Q02/Q03/Q04：补 CAS 的绕过口子与真实测试；
3. Q05/Q06：补异步/调度的关联标识；
4. Q07：处理 `version` 的两类具名例外；
5. Q08：补币种集成验证；
6. Q09/Q10/Q11/Q12：指标、E2E 与运行期证据。
