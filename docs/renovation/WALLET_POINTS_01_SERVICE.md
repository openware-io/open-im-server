# A380币 / 积分 从 IM 迁移到 SaaS（直接删除）

> 变更记录（v2）：A380币/积分唯一真源已收口到 SaaS（cst_wallet_*/cst_point_*）；IM 侧 coin/points 与 C 端（gv_chat_app）coin/points/旧预约展示全部直接删除，不再保留 @Deprecated 过渡。

## 0. 方案集声明

| 项目 | 内容 |
| --- | --- |
| 方案集 | `WALLET_POINTS` |
| 顺序号 | `01` |
| 实施边界 | `SERVICE` + `APP`（后端收口 + C 端下线） |

## 1. 背景与问题

- IM 体系：`user_coin_account`/`user_coin_ledger`（A380币）、`user_point_*`（积分），按 `user_id`，C 端钱包卡片读这里。
- SaaS 体系：`cst_wallet_account`/`cst_wallet_ledger`（A380币储值）、`cst_point_*`（积分），按 `customer_id`，KTV 组合收款已扣这里。
- 两套数据 → C 端显示与支付扣减不一致，资金与对账风险。

## 2. 边界原则（最终结论）

- A380币 + 积分 唯一真源 = SaaS；IM 只做身份/入口，不持有余额，不提供 `me/wallet`、`me/points` 之类只读旁路。
- C 端业务展示唯一入口 = A380 H5（`gv_saas_mobile`）；gv_chat_app 不再显示钱包/积分/旧预约。
- 积分代币是 SaaS 体系的闭环业务，最多只能从 IM 获取身份信息（open_id）用来定位用户是谁。

## 3. 实施步骤（直接删除）

1. **后端收口**：直接删除 IM 的 coin/points 控制器、应用服务、领域模型、持久化与 DTO；同时删除旧预约的积分抵扣字段（`pointDeductAmount`/`pointDeductNum`/`pointStatus`）。
2. **C 端下线**：gv_chat_app 直接删除 `CoinRepository`/`PointsRepository`/`ReservationRepository` 及对应卡片、列表、预约页面；业务余额/积分只在 A380 H5 展示。
3. **A380 H5 读 SaaS**：`gv_saas_mobile` 通过已有 SaaS 端点读余额，链路为：
   `IM OAuth → POST /identity/oauth/im/bind（accountId）→ POST /business/members（memberId）→ GET /business/members/{memberId}/wallet + /{memberId}/points`。
4. **积分获取**：签到/订单奖励/邀请当前无现有逻辑（均为 mock），未来在 SaaS 营销/积分域实现。

## 4. 待拍板决策点

1. **A380币单位语义**：IM coin 是「代币数量」，SaaS wallet 是「最小货币单位整数」——1 A380币 = 多少最小货币单位？（当前按 1 A380币 = 100 分）
2. **C 端调 SaaS 鉴权**：A380 H5 调 `/business/members/{id}/wallet` 用什么 token（复用 C 端 IM token 网关校验，还是 OAuth 换 SaaS token）？
3. **账本查询端点**：A380 H5 的 `cst_wallet_ledger`/`cst_point_ledger` 分页查询端点尚未实现（明细展示延后）。

## 5. 变更清单

- [x] IM coin/points 控制器、应用服务、领域、持久化、DTO 直接删除。
- [x] 旧预约积分抵扣字段删除。
- [x] gv_chat_app coin/points/旧预约展示删除（仅保留小程序/A380 H5 入口）。
- [x] A380 H5 通过 SaaS 端点读钱包/积分。
- [ ] 账本分页查询端点（cst_wallet_ledger / cst_point_ledger）。
- [ ] 积分获取逻辑迁 SaaS（未来）。
