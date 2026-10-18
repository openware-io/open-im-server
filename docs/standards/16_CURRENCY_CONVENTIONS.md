# 16 币种约定（租户级单一来源 · CNY/USD）

> 状态：已定稿并进入实现（2026-09-18）。本文是币种能力的**唯一权威约定**，后端、后台（`open-saas-admin`）、
> B 端/C 端（`open-saas-mobile`）、App（`open-chat-app`）一律照此实现；与本文冲突的旧写法（硬编码 `¥`/`元`/`CNY`）一律清理。
> 全场景影响面清单（逐条 `文件:行号`）见 `docs/currency-scope-inventory-2026-09-18.md`，实现前必须对照该清单逐项确认。

## 1. 目标与已定口径

| 决策项 | 定稿 |
| --- | --- |
| 支持币种 | **CNY（人民币，¥）**、**USD（美元，$）**；新增币种只扩展枚举与前端字典，禁止散落映射 |
| 默认币种 | **USD**（租户未配置即 USD；后端所有「缺省 CNY」的旧默认值都要改掉） |
| 配置层级 | **租户级唯一来源**（不按门店/组织分叉）；平台运营可改任一租户，租户管理员可改本租户 |
| 存量数据 | 不特殊处理，**全部按默认 USD**；切币种**不做汇率换算**，金额数字不变，只改符号与语义 |
| 历史单据 | **已结算单据锁定币种快照**：单据/流水落库时固化币种，之后改设置不改写历史 |

最小货币单位口径不变：DB 存**最小货币单位**（CNY 分 / USD cent）；禁止任何汇率换算。

## 术语与前置假设

| 术语 | 定义 |
| --- | --- |
| 币种（currency） | 由 ISO 三字母代码标识的货币。本规范只支持 `CNY`、`USD`；**不含**代币与积分。 |
| 最小货币单位（minor unit） | 落库金额的单位。库中存**最小单位整数**（CNY 分 / USD cent），不用浮点、不做汇率换算。 |
| `currencyCode` | 币种在接口与库中的字段名，取值 `"CNY" \| "USD"`；与既有 `PayIntentDto.currencyCode`、`cst_wallet_ledger.currency_code` 一致。 |
| 租户级单一来源 | 币种**只**由租户配置决定，不按门店/组织分叉；缺省、空值与非法值一律解析为 `USD`。 |
| 快照（snapshot） | 单据/流水落库时固化的币种；已结算单据的币种不随后续设置变更而改写。 |
| 代币 / 积分（token / point） | **支付工具与权益**，不是货币：不参与币种换算、不带符号、不显示币种码，只显示数量。 |
| 比例（ratio） | 代币与货币之间的关系系数，语义**冻结**；不得内嵌进金额或展示文案。 |
| 写穿（write-through） | 租户币种变更时同事务同步派生列（门店默认币种、法人主体币种），避免界面口径分叉。 |

**前置假设**：币种在租户内唯一；金额一律以最小单位整数落库；不存在跨币种结算；历史已结算单据不回改。

## 2. 单一来源（唯一权威）

- 存储：`tnt_tenant_config`（`租户服务`），`(tenant_id, store_id=0, config_key='currency')`，
  `config_value ∈ {CNY, USD}`。缺省（无行/空值/非法值）解析为 **USD**；写入非法值返回 `400 CURRENCY_UNSUPPORTED`。
- 读写接口（`租户服务`）：
  - `GET  /api/v1/admin/tenant/currency` → `{ currencyCode, symbol, minorUnitDigits, supported:[{code,symbol,label}] }`
  - `PUT  /api/v1/admin/tenant/currency` body `{ "currencyCode": "USD" }` → 同 GET 结构
  - **接口一律取签名上下文租户，不接受 `tenantId` 参数**（照 `TenantConfigController` 的边界写法）；平台运营改别的租户 = 先切到目标租户上下文。
  - 写权限：新增权限 `tenant.currency.manage`（「修改租户币种」），迁移授予 `tenant.owner` 与 `platform.operator`；读依赖租户上下文即可（C 端也要读到本租户币种）。
  - 审计：动作码 `tenant.currency.update`，`detailJson` 必须含变更前/后值与受影响的联动行数。
- **JSON 字段名统一用 `currencyCode`**（与既有 `PayIntentDto.currencyCode`、`cst_wallet_ledger.currencyCode`、`WalletAdminController` 视图一致），值为 `"CNY"|"USD"`。
- 代码侧唯一定义：`sdk/infrastructure` 的 `Currency` 枚举（`CNY("¥",2)`、`USD("$",2)`）+ `CurrencyResolver`（`current()` 读签名上下文，缺省 USD）。
  任何模块**不得**自建符号表，不得在业务代码里写 `"¥"`、`"元"`、`"CNY"`、`"RMB"` 字面量（测试与本文档除外）。

### 2.1 必须复用而不是新建的存量字段

后端**已存在**多币种列，本能力只做「单一来源 + 默认值 + 全链路透传」，**不得新增重复列**：

| 表 | 列 | 说明 |
| --- | --- | --- |
| `ord_order` | `currency_code char(3) NOT NULL` | 订单币种（结账快照） |
| `pay_intent` / `pay_transaction` | `currency_code char(3) NOT NULL` | 支付意图/流水币种 |
| `pay_channel_transaction` | `currency` | 渠道流水币种 |
| `cst_wallet_account` | `currency_code` | 钱包账户币种（已做跨币种拒绝） |
| `tnt_store` | `default_currency` | 门店默认币种 → **租户币种变更时同事务写穿**（保证门店级界面一致） |
| `tnt_legal_entity` | `currency_code` | 法人主体币种 → 同上写穿或标记派生 |

新增列只允许出现在**确实缺失**的位置（例如采购入库单、积分调整、无币种列的退款/账单对象），并遵循 §5。

### 2.2 切换币种时的联动规则（必须显式实现，不许静默）

1. **门店写穿**：同事务把所有 `tnt_store.default_currency` 改为新币种。
2. **钱包/储值余额保护**：若存在**非零余额且币种不同**的钱包/储值账户，`PUT` 默认返回
   `409 CURRENCY_SWITCH_BLOCKED_BY_BALANCE`（body 含受影响账户数），**不做静默改写**；
   仅当请求显式带 `migrateBalances: true` 时才把这些账户 `currency_code` 改为新币种（**金额数字不变**，写审计与前后值）。
3. **支付方式能力联动**：现金不依赖币种；微信/支付宝等渠道**仅支持 CNY**。租户币种为 USD 时：
   可用支付方式列表中这些渠道要么不返回、要么带 `available:false + reason`；后端创建支付意图时校验并返回
   `CURRENCY_PAYMENT_METHOD_UNSUPPORTED`，**不得**让用户走到渠道下单才失败。
4. 联动全部写审计（同一动作码，detail 含 before/after/受影响行数）。

## 3. 传播（下游如何拿到币种）

1. **签名上下文带币种**：两条签发路径都要写 JWT claim `currency`（`currencyCode` 的值）：
   - 后台 BFF：`运营控制台` `POST /api/v1/admin/context/select`
   - C 端/客户端：`身份服务` `POST /api/v1/auth/context/select`
   - `sdk/infrastructure` 侧解析该 claim（**老 token 无 claim 时回退 USD，不得 401**）；为控制改动面，
     优先用旁路 holder（与 `TenantContextHolder` 同款、同一个已验证 token 的过滤器写入/清理），不要改 `TenantContext` 规范构造器。
2. **两条 context select 的响应体都带 `currencyCode`**（前端唯一启动来源）。
3. **服务端一切使用币种的地方**（导出 Excel/CSV、打印小票、短信/邮件模板、报表表头、服务端拼的展示文案）统一走
   `CurrencyResolver.current()`；**单据有快照时以快照为准**。先扫出所有硬编码位置再逐个处理。
4. **前端**：全局 store 持有 `currencyCode`（缺省 USD），所有金额渲染走 `formatMoney(minor, currencyCode?)`；
   切换币种后**全站响应式更新**，禁止各页面各请求一套。
5. **网关兜底**：响应注入 `X-Currency` 响应头（取自上下文，缺省 USD）并加入 CORS `exposedHeaders`；兜底不能替代单据快照。
6. **快照优先**：记录自身带 `currencyCode` 的以记录为准；列表/报表混排必须显示币种标识；跨币种聚合要么拒绝要么显式标注。

## 4. 金额表示与前端契约

- API 金额字段保持既有类型与单位（最小货币单位），不重命名、不改数量级。
- 需要锁定或可能跨币种的响应，**带同级 `currencyCode` 字段**。
- 前端唯一格式化入口 `formatMoney(minor, currencyCode?)` → `¥100.00` / `$100.00`（0、负数、大额千分位、未知币种回退都要正确）。
  **格式逐字统一（三端必须完全一致，禁止各自加空格）**：`[负号][符号][千分位金额]`，符号**紧跟**数字、无空格，固定两位小数（按币种小数位），
  例：`¥1,234.56`、`$8,880.00`、`-¥5.00`；空值/非法值统一占位 `—`（不得出现 `¥NaN`）。
  符号映射集中一处（`constants/currency.js` 或等价文件）；金额文案不再拼「元」，需要币种名称用其 label。
- 禁止视图里散落 `÷100`/`×100`；换算只允许在 `formatMoney` 内部与后端。
- **三套前端（`open-saas-admin`、`open-saas-mobile`、`open-chat-app`）必须同改**：符号目前在 `'¥ '`（后台/移动端）与 `'¥'`（App，自带多币种符号表）各写一套，
  只改一处会出现「同一笔钱不同端不同符号」。App 侧应复用其既有格式化实现并改为使用服务端币种。

## 5. 快照规则（已结算单据锁定币种）

- **已有币种列**（§2.1）不得重复新增：补齐「确实写入」的逻辑（空值按当时租户币种回填/写入）即可。
- 对**确实没有币种列**的金额落库对象新增 `currency_code varchar(3) NOT NULL DEFAULT 'USD'`（列名与既有保持一致），至少核实：
  采购入库/领用单、积分调整、退款单、账单/明细、储值/代币流水、找零与支付分腿记录（以影响面清单 §1 为准）。
- 写入时机：结账、收款、退款、充值、积分调整、采购入库等**金额落库的同一事务**内写入当时租户币种。
- 迁移回填：历史行按用户口径回填 **USD**。
- 展示：历史单据显示其快照币种；**改设置不得改变历史单据显示金额与符号**（回归测试必须覆盖）。

## 6. 现金场景清单（必须逐项落地）

收银结账（开台/加项/结台账单、实时预估）、包厢计价（房型单价 + 服务单价 = 合计口径）、加项与作废、套餐/优惠/满减/税、
混合支付（现金 + 扫码/POS）与找零、支付流水与对账、退款（全额/部分/线下）、会员钱包（余额/充值/赠送/消费）、储值、代币、积分、
库存采购价/入库成本/成本与毛利报表、日结/班结/营业报表、导出（Excel/CSV）、打印小票、短信/邮件模板、审计日志里的金额、
C 端报价与我的预约、B 端订单/库存/看板、App 收银/结账/班次页、平台侧租户与套餐相关金额。
每一项在 `docs/currency-scope-inventory-2026-09-18.md` 中都有「界面 → 接口 → 落库字段 → 展示 → 二次消费点（打印/导出/对账/退款/日结）」的对应行。

## 7. 测试与守卫（缺一不可）

- 后端：默认 USD、非法值 400、`tenant.currency.manage` 权限与越权、审计含前后值、上下文 claim 传播（含老 token 回退）、
  门店写穿、钱包余额拦截与 `migrateBalances`、支付方式能力校验、快照写入与「改设置不改历史」、导出/模板符号随币种变化。
- 前端（三个仓库）：`formatMoney` 单测（CNY/USD/0/负数/大额/未知币种回退）、**硬编码符号源码守卫**（`KNOWN_DEBT` 不得新增，尽量清空）、
  切换币种后关键界面（订单/账单/商品/库存/钱包/报表/C 端报价/App 收银页）断言同步变化。
- 端到端：切 USD → 逐界面核对；再切 CNY → 复核（同一批数据，符号全站一致、金额数字不变、历史单据不变）。

## 8. 上线与验收

- 按发版规范发布（不升版本、同 tag 覆盖），kind 与 ACK 使用同一份 release manifest。
- 验收清单：后台订单/账单/支付/退款/钱包/库存/商品/报表/审计、C 端报价与预约、B 端订单与库存、App 收银/结账，逐项给出接口或界面证据。
- 回退：币种是配置项，回退只需把 `currency` 改回；若改过钱包账户币种（`migrateBalances`），需按审计记录人工核对。

## 9. 代币（钱包品牌）与金额的关系（ratio 语义冻结）

`tnt_tenant_config` 的 `wallet_brand_name` / `wallet_ratio`（读写接口 `GET|PUT /api/v1/admin/tenant/config`，
见 `租户服务` 的 `TenantConfigController`）定义「储值/代币」的品牌展示名与兑换口径。

| 项 | 冻结口径 |
| --- | --- |
| `wallet_brand_name` | 代币品牌**展示名**（缺省 `A380币`），租户级可自定义；只影响界面文案，不是币种 |
| `wallet_ratio`（默认 `100`） | **1 个主单位（1 元 / 1 美元，随租户币种，默认 USD）= ratio 个代币** |
| 用途 | **仅用于展示与文案**（例如「充 100 元得 10,000 A380币」的界面提示、代币数量换算展示） |
| 硬约束 | **禁止参与任何入账 / 扣减 / 对账 / 退款 / 日结计算**，**禁止把代币数量落库为金额列**；入账一律只用**最小货币单位**（CNY 分 / USD cent）的整数金额，不按 ratio 折算 |
| 跨币种 | **不做折算**；ratio 随租户币种解释（§1：切币种不做汇率换算，金额数字不变） |

- 落库金额（钱包账本/流水、支付、退款、班次日结）恒为最小货币单位整数；`ratio` 只出现在展示层与文案层，
  任何一处把它写进金额计算都是缺陷，评审按缺陷处理。
- **服务端只读展示投影（冻结）**：三端展示代币数量时，统一由服务端下发，避免各端各算一套：
  `客户服务` 的钱包/流水响应带 `tokenAmount`（**字符串纯数量**，无货币符号、无币种，公式
  `最小货币单位 ÷ 100 × ratio`，HALF_UP）与 `tokenBrandName`；品牌名与 ratio 经内部接口
  `GET /internal/iam/tenant-config/{tenantId}/wallet-token`（`租户服务`，
  HMAC 版本 2，调用方 `客户服务` 必须在 `expected-source` 白名单内）读取，
  缺行/空值/非法/ratio≤0 一律回落 `A380币` + `100` 且不抛错。
- 该投影是**展示字段**（`@TableField(exist=false)`），只读、不落库、不参与任何货币合计与对账；
  内部机器契约 `/internal/customer/wallets/*`（组合收款扣减/归还）**只有货币金额**，不带 `tokenAmount`。
- **积分**是**个数**（1:1，不做任何换算）：`GET /me/points`、`GET /business/members/{id}/points`
  的 `availablePoints`/`frozenPoints`/流水 `points`/`balanceAfter` 都是积分个数，响应**不含币种**、
  不含 `tokenAmount`/`tokenBrandName`（流水行的币种快照仍留在库里，仅供报表/审计）。
- **禁止反向换算用于写入**：组合收款的 `payments[].amount` 恒为最小货币单位整数、各腿合计 = 应收；
  前端允许「按数量输入」，但必须在提交前自行按 ratio 折回金额（`tokensToMinor`），
  不得把代币/积分数量直接作为金额提交。
- 前端文案由三端各自按本口径渲染；`formatMoney` 只负责**最小货币单位 → 符号+金额**的展示（§4），
  不得内嵌 ratio；代币/积分走 `formatTokens`/`formatPoints`，**只出数量**（千分位整数）：
  绝不出现 `¥ / $ / 元 / 币种码`，也**不拼品牌名、「积分」、「个」这类单位**——
  名字由支付方式名、列头、表单标签与卡片标题承担（例如列头「A380币余额」下的值就是 `1,000`）。
  品牌名（`wallet_brand_name`）与「积分」只作为**标签**出现，不作为值的后缀。

## 模板与示例

**新增一个带币种的字段/接口时，按此清单落地（示例，不构成强制命名）**：

1. 库列：`currency_code char(3) NOT NULL`，落库时取**当时**的租户币种（快照语义）；不要给 `DEFAULT 'CNY'`。
2. 后端取值：一律经 `CurrencyResolver.current()`；**禁止**在业务代码写 `"CNY"`、`"¥"`、`"元"` 字面量。
3. 接口字段名：`currencyCode`；响应同时给 `minorUnitDigits`，由前端决定小数位，不在服务端拼字符串。
4. 切币种联动：纳入 §2.2 的同一事务；变更审计写 `tenant.currency.update`，`detailJson` 含前后值与受影响行数。
5. 前端展示：金额走 `formatMoney`（符号紧贴数字、无空格）；代币/积分走 `formatTokens`/`formatPoints`（只出数量）。

```text
请求： PUT /api/v1/admin/tenant/currency      body { "currencyCode": "USD" }
响应： { "currencyCode": "USD", "symbol": "$", "minorUnitDigits": 2,
         "supported": [ { "code": "CNY", "symbol": "¥", "label": "人民币" },
                        { "code": "USD", "symbol": "$", "label": "美元" } ] }
```

## 门禁与人工检查项

**可自动化**（必须进统一校验入口，不得选择性跳过）：

| 门禁 | 判据 |
| --- | --- |
| 字面量扫描 | 业务代码（测试与本文档除外）不出现 `"¥"`、`"元"`、`"CNY"`、`"RMB"` 字面量 |
| 符号表唯一 | 符号/小数位只来自 `sdk/infrastructure` 的 `Currency` 枚举；不得自建映射 |
| 缺省口径 | 无行/空值/非法值解析为 `USD`；写入非法值返回 `400 CURRENCY_UNSUPPORTED` |
| 快照不回改 | 改租户币种后，已结算单据的 `currency_code` 不变 |
| 写穿一致 | 租户币种变更同事务写穿门店与法人主体派生列，无残留旧值 |

`字面量扫描` 已由 `scripts/validate/validate-currency-literals.ps1` 实现并接入统一验证入口：扫描服务模块 `src/main/java`（剥离注释），测试、文档、`Currency` 枚举本身与网关协议兜底不在范围内。
`符号表唯一` 由 `scripts/validate/validate-currency-symbols.ps1` 实现：符号、中文名与 `put/Map.of("USD"|"CNY", ...)` 映射只允许出现在 `Currency` 枚举。

**人工检查项**（评审时确认）：

1. 新增的币种类字段是否**复用**了 §2.1 的存量列，而非重复建列。
2. 现金场景（§6）是否逐项有接口或界面证据，未留静默分支。
3. 代币/积分展示是否**只出数量**，未被拼上符号、币种码或单位后缀。
4. 变更审计的 `detailJson` 是否足以还原"改了谁、从什么改成什么、影响多少行"。

