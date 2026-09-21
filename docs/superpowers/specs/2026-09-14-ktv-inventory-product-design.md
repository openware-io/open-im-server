# KTV 仓库与商品上架设计

## 1. 目标与边界

本方案为 KTV 第一版增加基础仓库管理和商品管理，并把“入库 → 上架 → 加项可见”固化为服务端规则。第一版只解决门店日常酒水/食品等物料的入库、库存查询、调整、商品上架和订单消耗；采购、供应商、多仓调拨、批次效期、复杂盘点、成本核算和配方 BOM 留到后续版本。

改造不改变 A380 C 端和 A380 后台的 OAuth/OIDC 会话隔离，也不改变已有 KTV 包厢、预约、计时、服务人员和收银流程。新接口继续经过网关的租户上下文和 IAM 授权。

## 2. 当前实现与问题

- `ord_catalog_item` 同时承担商品、服务、套餐和加项目录，没有物料、库存余额或库存流水关系；所有 `ACTIVE` 商品都会被目录接口返回。
- `CatalogController` 直接操作 Mapper 并返回 PO，`OrderItemController` 也直接读取目录 PO，和工程规范要求的 `api → application → domain ← infra` 分层不一致。
- `OrderItemController` 实际要求 `catalogItemId`，但 SaaS 后台仍保留“自定义加项”入口并发送没有目录 ID 的请求，该入口会返回 `CATALOG_ITEM_REQUIRED`。C 端已经只传目录 ID。
- 目录和加项按租户过滤，但目录 ID、门店归属和订单门店没有在同一个用例内完成校验，存在同租户跨门店引用风险。
- 订单明细只有价格/名称快照，没有库存扣减事实；订单取消、作废或退款也没有库存冲销流水。

这里的“冲突”不是程序偶发报错，而是现有模型对同一件事给出了两套互相矛盾的定义：当前目录中 `ACTIVE` 就代表“可以被加项”，新需求要求“有库存且已上架才可以被加项”。如果只在页面隐藏按钮，接口仍可被直接调用，库存和账单最终会失真。

| 冲突 | 现状 | 具体后果 | 方案决策 |
| --- | --- | --- | --- |
| 商品语义 | 目录项既可能是酒水、服务、加钟、套餐 | 无法判断是否需要库存 | 商品与服务分开建模；库存商品必须绑定物料，服务/加钟保留非库存类型 |
| 上架语义 | `ACTIVE` 既表示启用又表示可售 | 入库前也能出现在 C 端目录 | 商品使用 `DRAFT/ON_SHELF/OFF_SHELF`；目录由服务端按状态和可售库存过滤 |
| 接口契约 | 后台有“自定义加项”名称/价格输入 | 前端不传 `catalogItemId`，服务端必然返回 `CATALOG_ITEM_REQUIRED`；即使放开也会绕过商品和库存 | “自定义”只表示从已配置商品/服务中选择数量和备注，不允许自由填写名称或价格 |
| 审批与库存 | C 端加项先 `PENDING_APPROVAL`，后台确认才 `ACTIVE` | 提交时扣库存会造成拒绝项占库存；确认时不锁库存会超卖 | 提交不扣库存；确认/代客加项时加行锁并原子扣减，失败则保持待确认 |
| 数据归属 | 目录 Controller 直接读写 `ord_catalog_item` | 商品、库存、订单各自修改，无法保证同一事务 | order 服务内由商品库存应用服务统一编排，Controller 不直接访问 Mapper |
| 门店隔离 | 目录只按租户过滤，`catalogItemId` 查询未校验订单门店 | 同租户不同门店可能串用商品和价格 | 每次读取、上架、加项同时校验租户、组织、门店归属 |
| 旧数据兼容 | 现有酒水/小吃种子数据全部 `ACTIVE` | 一刀切改为库存规则会导致目录突然为空 | 先双读和草稿回填；服务/加钟继续可用，库存商品逐店补期初库存后再开启开关 |

## 3. 领域边界与权威关系

第一版仍由 `platform-order-service` 作为订单、商品目录和库存子域的唯一数据所有者，避免订单确认与库存扣减之间出现跨服务分布式事务。代码按子域拆包，未来库存量或仓库数量达到独立扩缩容条件时再拆服务。

```text
库存物料（可入库）
    └─ 库存余额 + 库存流水
          └─ 商品（绑定一个物料，预留 BOM）
                └─ 上架商品目录（兼容 ord_catalog_item）
                      └─ C 端/后台加项 → 订单明细快照
```

物料是库存事实，商品是销售事实，目录是展示投影，订单明细是不可变交易快照。下架只影响新加项，不删除历史订单。

对前台和运营人员而言，商品与服务统一称为“可售项目”：商品带有库存关联并在生效时扣库存，服务/加钟没有库存关联但同样必须先配置、启用并进入目录。这样“自定义加项”表达的是对已配置可售项目的数量、备注进行自定义，而不是临时创造一个新项目。

## 4. 数据模型

新增表均由 order 服务 Flyway 管理，版本从现有 `V10` 之后递增；实施前先在 `docs/ENGINEERING_RULES.md` 的领域/表前缀登记表登记“商品库存子域”，不修改已执行迁移，不跨服务建外键。

### 4.1 `ord_inventory_material`（物料档案）

字段：`id`、`tenant_id`、`store_id`、`material_code`、`name`、`category`、`unit`、`safety_stock`、`status`（`ACTIVE/INACTIVE`）、审计字段、`version`。唯一键为 `(tenant_id, store_id, material_code)`。物料停用后禁止新入库和新商品绑定，历史流水保留。

### 4.2 `ord_inventory_stock`（库存余额）

字段：`id`、`tenant_id`、`store_id`、`material_id`、`on_hand_qty`、`reserved_qty`（第一版固定为 0，预留订单预占）、`version`、审计字段。唯一键为 `(tenant_id, store_id, material_id)`。可用库存为 `on_hand_qty - reserved_qty`，数量使用 `DECIMAL(20,6)`，禁止负库存。

### 4.3 `ord_inventory_transaction`（库存流水）

字段：`id`、租户/门店/物料标识、`transaction_type`（`RECEIPT/CONSUME/ADJUST_IN/ADJUST_OUT/REVERSE`）、`quantity_delta`、`quantity_before`、`quantity_after`、`source_type`、`source_id`、`idempotency_key`、`reason`、操作人和时间。唯一键为 `(tenant_id, idempotency_key)`；流水只追加，不允许编辑或物理删除。

### 4.4 `ord_product`（商品档案）

字段：`id`、`tenant_id`、`store_id`、`product_code`、`name`、`category`、`unit`、`sale_price`、`material_id`、`stock_controlled`、`status`（`DRAFT/ON_SHELF/OFF_SHELF`）、`sort_order`、`description`、审计字段、`version`。第一版一个商品绑定一个物料；预留 `ord_product_component` 以支持套餐/BOM。唯一键为 `(tenant_id, store_id, product_code)`。

### 4.5 旧目录兼容

不删除 `ord_catalog_item`，新增 `product_id`、`listing_source` 和必要索引。库存商品由 `ord_product` 权威，`ord_catalog_item` 作为兼容展示投影；服务类、加钟类等不消耗库存的旧目录项可继续使用。目录接口只返回：非库存服务类的 `ACTIVE` 项，以及 `ON_SHELF` 且可售的库存商品。历史 `catalogItemId` 仍可被订单明细读取，名称/价格继续以订单快照为准。

## 5. 核心业务规则

1. 创建物料只建立档案，不产生库存；入库单提交才增加 `on_hand_qty` 并写 `RECEIPT` 流水。
2. 商品上架必须绑定同门店、启用中的物料，编码、名称、单位和售价合法；没有绑定物料不能上架。库存为零时商品可保留上架状态，但默认不进入 C 端可售目录，后台显示“缺货”。
3. C 端提交加项只允许 `ON_SHELF` 商品或非库存服务目录项，提交状态为 `PENDING_APPROVAL`，不扣库存。
4. 后台代客加项或确认 C 端加项时，在同一事务锁定库存余额；可用库存不足则返回 `INVENTORY_INSUFFICIENT`，订单明细不进入 `ACTIVE`。成功后写 `CONSUME` 流水，使用幂等键 `order-item:{id}:consume`。
5. 订单作废、已生效加项撤销或退款需要恢复库存时写 `REVERSE` 流水，幂等键为 `order-item:{id}:reverse`；禁止直接修改历史流水。
6. 商品下架仅阻止新加项，不能删除商品、物料、库存或订单历史。价格、单位、名称在订单明细中继续保存快照。
7. 所有读取和写入都校验 `tenant_id + store_id`；运营人员只能访问 IAM 授权的租户/组织/门店范围，C 端只能读取当前会话所属门店的可售目录。
8. 订单加项请求只接受已配置项目的标识、数量和可选备注；服务端永远从商品/服务档案读取名称、单位、价格和库存属性，客户端提交的名称和价格字段一律忽略或拒绝。

## 6. API 设计

### 后台接口（需要细粒度 IAM 权限）

| 用例 | 方法与路径 | 说明 |
| --- | --- | --- |
| 物料列表/详情 | `GET /api/v1/admin/inventory/materials` | 按门店、分类、状态查询，附可用库存 |
| 新建/停用物料 | `POST/PUT /api/v1/admin/inventory/materials` | 编码在门店内唯一 |
| 入库 | `POST /api/v1/admin/inventory/receipts` | 明细、数量、单价可选、备注、幂等键 |
| 库存调整 | `POST /api/v1/admin/inventory/adjustments` | 增减数量和必填原因 |
| 库存流水 | `GET /api/v1/admin/inventory/transactions` | 只读、分页、可按来源追溯 |
| 商品列表/创建/编辑 | `GET/POST/PUT /api/v1/admin/products` | 草稿状态保存 |
| 上架/下架 | `POST /api/v1/admin/products/{id}/on-shelf`、`.../off-shelf` | 上架执行物料和门店校验 |

### 兼容接口

保留 `GET /api/v1/business/catalog/items`、订单加项和服务人员接口。目录列表改由应用服务返回 `CatalogItemView`，不再直接返回 PO；新增商品通过商品接口完成，旧目录写接口进入兼容期并只允许创建非库存服务项，库存商品必须走商品上架流程。

`/api/v1/admin/inventory/**` 和 `/api/v1/admin/products/**` 需要在网关新增到 order 服务的路由，并纳入现有 B 端会话认证、CSRF、审计和租户上下文过滤；不能直接暴露 order 服务端口，也不能加入 C 端路由。

### 错误码

统一使用 `MATERIAL_NOT_FOUND`、`MATERIAL_SCOPE_DENIED`、`PRODUCT_NOT_READY_FOR_SHELF`、`PRODUCT_OFF_SHELF`、`INVENTORY_INSUFFICIENT`、`INVENTORY_IDEMPOTENCY_CONFLICT`、`STORE_SCOPE_DENIED`，保持现有全局异常格式。

## 7. 权限与会话安全

在 tenant 服务新增 IAM 权限种子（建议下一版本迁移）：`inventory.material.view/manage`、`inventory.receipt.create`、`inventory.adjust`、`inventory.transaction.view`、`product.view/manage`、`product.publish`、`product.unpublish`。租户管理员和店长按门店范围授予；收银员默认只有商品/库存读取和入库权限，C 端用户不授予任何后台权限。

Controller 只提取会话上下文并调用应用服务；权限、租户/门店归属、状态转移、幂等和库存锁定放在应用/领域层。不能用 C 端 Cookie、B 端 Cookie 或 `biz_token` 互相回退，保持现有 A380 C/B 会话隔离。

## 8. 页面与交互

SaaS 后台新增两个租户菜单：`仓库管理` 和 `商品管理`。仓库页包含物料档案、入库、库存余额、流水四个 Tab；商品页包含草稿/已上架/已下架筛选、绑定物料、价格和上下架操作。上架按钮显示校验失败原因，库存为零显示缺货而不是静默消失。

订单/KTV 点单页继续显示目录，但改为统一的商品/服务选择器；将现有“自定义加项”改名为“选择商品/服务”，允许调整数量和填写备注，不允许输入新的名称或价格。每次提交都传 `catalogItemId`（后续可扩展为 `productId`），由服务端读取当前有效配置并保存名称、单位和价格快照。C 端目录按分类展示可售商品，缺货商品不显示；已有预约、包厢、计时、结台、收银页面不改变。

## 9. 迁移与发布策略

1. **数据库阶段**：新增表、索引、权限种子和兼容字段；空库 Flyway、升级库和回滚备份验证通过后才能合并。
2. **双读阶段**：商品接口和后台页面上线，旧目录继续提供服务/加钟项；对现有酒水/小吃目录生成草稿商品，不自动伪造库存。
3. **数据切换阶段**：运营人员为真实商品补齐物料、执行期初入库、确认价格后逐项上架；按门店开启 `inventory_control` 特性开关。切换前导出目录与订单快照，切换后核对可售目录数量。
4. **收敛阶段**：库存商品全部由商品接口维护，兼容目录写接口只保留服务项；观察一个完整营业日后再删除前端旧入口。任何阶段都可关闭特性开关，历史订单和库存流水不回滚删除。

发布遵循服务域局部 `clean verify`、迁移契约测试、API 兼容性检查、不可变镜像和版本清单；不得修改已发布 `V1`~`V10`。ACK 发布前先在 Kind 完成部署与数据造数，随后浏览器后台和 vivo 真机回归；测试数据使用独立编码并在验收后按清单精准删除。

## 10. 验收矩阵

- 物料编码重复、跨门店访问、停用物料入库均被拒绝。
- 入库、增加/减少调整、流水前后余额、幂等重试结果一致，库存不会为负。
- 未绑定物料、未入库商品不能上架；下架后 C 端目录立即不可见；零库存显示缺货且不能加项。
- C 端加项待确认不扣库存；后台确认成功只扣一次；库存不足不改变订单状态；作废/冲销只恢复一次。
- 旧服务类/加钟类目录、包厢预约、开台计时、结台、结算和收银保持原行为。
- 未授权运营账号访问新菜单和写接口返回 `403`；普通 IM 用户仍只能进入 A380 C 端，不能进入后台。
- 多门店、跨租户、并发扣库存、重复提交、刷新重试、会话过期和网关重试均通过。

## 11. 影响面复核（补充）

以下现有调用不能遗漏，否则商品上架完成后仍会出现“后台能点、客户端失败”或金额统计不一致：

- `gv_chat_app` 的 `KtvTimingScreen` 目前使用本地 `_menu`（`item_001` 等字符串），没有读取真实目录；`KtvApiClient.addItems` 发送批量 `items` 结构并按 `KtvOrder` 解析响应，而 order 服务当前接口接收单个 `catalogItemId` 并返回订单明细。两端契约必须统一：保留现有单项接口语义，客户端改为读取目录并传数字目录 ID；若确需批量，新增独立 `/items/batch`，不能改变旧接口的请求/响应含义。
- `gv_saas_mobile/c-end` 已通过目录 ID 走单项加项，但必须增加商品状态、缺货和库存不足提示；`gv_saas_admin` 订单页要移除自由名称/价格请求，目录管理改为商品/服务选择器。
- `KtvServerSessionApplicationService` 会把服务人员费用写入 `ord_order_item`；该类服务不应触发库存扣减，但仍要保留服务目录 ID、价格快照和原有计费逻辑。
- 报表当前按 `item_type = 'ADD_ON'` 统计加项；接入库存商品后需区分“销售商品数、服务加项数、库存消耗量”，不能把库存商品改成 `ADD_ON` 后造成旧报表漏统计或重复统计。报表只读接口应通过订单服务提供的稳定查询契约获取库存汇总，禁止新增跨服务 Mapper 直连。
- OpenAPI 文件、SaaS admin API 封装、移动端 JS API、Flutter DTO/测试、网关路由白名单和会话过滤测试都必须同步更新；任何一端未更新都不能发布。
- 菜单由 `/admin/menus` 动态返回时，要给新权限配置菜单可见性；前端路由的 `scope: TENANT` 不能代替服务端权限判断。
- 库存流水、商品上下架和加项扣减必须写现有审计链路；需要给订单明细增加“库存处理状态/流水引用”查询能力，便于对账而不暴露内部 PO。

## 12. 实施拆分与影响评估

建议分三次合并，每次独立可回滚：

1. **库存内核**：迁移、领域模型、入库/调整/流水、库存锁定和单元/集成测试。影响 order 服务数据库和订单加项事务。
2. **商品上架**：商品 CRUD、上下架、目录兼容投影、IAM 权限、后台页面和 C 端目录过滤。影响 order API、SaaS admin、SaaS mobile。
3. **交易收敛**：订单确认/作废/退款库存冲销、旧入口下线、特性开关、Kind/ACK 与真机回归。影响订单状态流转、报表和发布配置。

每阶段都先完成自动化测试和本地 Kind 验证，再进行浏览器后台与 vivo 真机验收；未通过不得进入下一阶段或发版。
