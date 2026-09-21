# KTV 预约改为「预约房型」（到店分配包厢）· 改造规格

> 状态：已定稿并进入实现（2026-09-18）。用户原话：「KTV 预定界面需要调整，预约应该是预约的房型，界面显示『选择包厢』改为『选择包厢类型』，
> 因为一个 KTV 包厢可能有几十上百个，但是房型可能不会很多，而且具体的包厢号也是应该到店后才分配的。」

## 1. 业务结论（唯一口径）

1. **预约对象 = 房型（`res_room_type`）**，不是具体包厢。C 端/B 端/后台的预约入口一律选「包厢类型」。
2. **具体包厢到店后由门店分配**：预约阶段不锁房、不显示包厢号；分配到具体包厢后才允许开台计时。
3. 因此预约页展示的信息是：房型名称、**房型报价（房型单价 + 服务单价 = 合计，见 `docs/standards/16…` 与结台同口径）**、可容纳人数/说明、
   门店与时段；**不展示**具体包厢号，并明确提示「具体包厢到店后由门店分配」。
4. 术语：界面一律「**包厢类型/房型**」；`包厢` 只用于已分配的具体包厢与开台/占用场景。

## 2. 数据模型

- `ord_reservation` 新增 `room_type_id bigint unsigned NULL`（`KEY idx_ord_reservation_room_type (tenant_id, store_id, room_type_id, start_at, status)`）。
- `resource_id` 语义变更：**预约创建时不写**；由「到店分配包厢」写入，表示该预约最终使用的具体包厢。
- 兼容：历史行可能只有 `resource_id` 没有 `room_type_id` → 读取时按「旧数据」处理（列表显示旧包厢名、开台仍用既有 `resource_id`）；
  **不回填**（跨服务数据不在同一迁移里可靠取得），新数据一律房型驱动。列注释必须写清语义，迁移文件顶部写明本决策。

## 3. 接口契约

| 场景 | 接口 | 说明 |
| --- | --- | --- |
| 创建预约 | `POST /business/reservations`（C 端）/ 后台同语义 | 请求带 `roomTypeId`（必填）；`resourceId` 不再由调用方传（传了忽略或 400，二选一但必须显式测试）。响应带 `roomTypeId/roomTypeName/roomTypeCode` 与报价（若有）。 |
| 创建校验 | — | ① 房型存在、启用、属于该门店；② 该房型下至少有 1 个启用包厢（否则 `ROOM_TYPE_NO_ROOM_AVAILABLE`）；③ **不再做「具体包厢可用」校验**（到店才分配）。 |
| 超订保护 | — | 同一门店 + 同一房型 + 时段重叠（左闭右开）的未取消预约数 ≥ 该房型启用包厢数 → `409 ROOM_TYPE_FULL`（提示改选其它房型/时段）。这是软保护，实际可用性以到店分配为准。 |
| 到店分配包厢 | 新增 `POST /admin/reservations/{id}/assign-room` body `{ resourceId }` | 校验：预约未取消未开台；包厢存在、属于该预约房型、当前可分配（复用既有占用门禁 `ResourceStateClient`，fail-closed）；写 `resource_id`，**状态不变**（提前锁房 ≠ 客人到店，见 `KTV_RESERVATION_ORDER_STATE_FLOW.md` §4.1 修订）；**审计动作码 `reservation.assign_room`**（含 before/after）；幂等：已分配同一包厢返回成功，换包厢需显式覆盖并留痕。 |
| 到店（不带分配） | 现有 arrival | 保留；`CONFIRMED → ARRIVED` 并写 `arrived_at`（V25 起）；**开台前必须有 `resource_id`**。 |
| 未到店 | 新增 `POST /admin/reservations/{id}/no-show` | 到店前（`PENDING/CONFIRMED`）且已过预约开始时间 → `NO_SHOW`；预约开始时间未到 → `409 RESERVATION_NOT_STARTED`；审计 `reservation.no_show`。用于超时未到的预约释放包厢预约位。 |
| 到店预约开台 | 现有 openTable | 接受 `ARRIVED` 或 `CONFIRMED`（后者隐含登记 `arrived_at`）；断言 `resource_id != null`，否则 `409 RESERVATION_ROOM_NOT_ASSIGNED`；仍用该包厢建房态会话。已开台（`CONVERTED` + `order_id`）重复提交幂等返回既有订单。 |
| 房型报价 | `/business/ktv/pricing` | 支持按 `roomTypeId`（+storeId）报价，返回今已实现的 `roomUnitPrice/serverUnitPrice/combinedUnitPrice/displayText`；预约页用合计口径。（内部仍可支持 `resourceId` 以兼容其它调用） |

## 4. 界面

- **C 端**：选包厢页标题/交互改为「选择包厢类型」；卡片 = 房型（名称、`合计/小时`、房型说明），**不出现包厢号**；确认页与「我的预约」显示房型 +「到店后由门店分配包厢」。
- **B 端/后台**：预约列表与详情按房型展示（房型名 + 门店 + 时段 + 状态）；新增「分配包厢」操作（下拉只列该房型下当前可用的包厢），分配成功后展示包厢名与「开台」按钮；未分配时「开台」按钮禁用并提示原因。
- 术语与文案走既有术语表/守卫（禁用词表不变，统一「包厢」「包厢类型」）。

## 5. 测试（必须）

- 后端：创建预约只认房型（传 resourceId 的行为有显式测试）、房型无启用包厢 409、超订 409、到店分配包厢的机房型不匹配 400、包厢不可用/占用 fail-closed、开台前未分配 409、分配幂等与换包厢留痕、旧数据（只有 resource_id）仍能开台与列表展示、报价按房型返回合计。
- 前端（C 端/B 端/后台）：不出现包厢号选择、文案为「选择包厢类型」、房型价格按合计展示、未分配时开台禁用与提示、分配成功后列表刷新。
- 端到端：C 端按房型预约 → 后台看到房型预约 → 分配具体包厢 → 开台 → 结台（金额与房型报价口径一致）。

## 6. 交付与约束

- 按发版规范发布（不升版本、同 tag 覆盖），kind 与 ACK 同一份 manifest；与币种能力可同批发布。
- 涉及仓库：`gv_im_server`（order 模块为主，resource 模块只读复用房型/包厢接口）、`gv_saas_admin`、`gv_saas_mobile`（C 端 + B 端）。
- 每个仓库一个中文提交；不改版本号、不 push、不碰 k8s 用户 WIP 文件。
