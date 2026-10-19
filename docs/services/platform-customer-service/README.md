# platform-customer-service

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- **客户档案**（`cst_member`）：没有会员等级/权益/成长值业务之前，这张表存的就是**客户**，后台入口叫「客户管理」
- 客户 ↔ IM 账号关联（`im_account` / `im_username` / `im_bound_at`）与去重，避免同一个 IM 用户建成多条客户
- 客户姓名检索（`cst_member_name_token` 盲索引：1/2/3-gram + SHA-256，密文姓名可「包含」搜索）
- 储值钱包与积分账本
- 钱包充值 / 退款、积分调整
- 内部余额 / 积分扣减与释放（供支付组合收款调用）

## 端口

- 服务直连：`4160`（`server.port`）
- 网关公开入口：`/api/v1/business/members/**`、`/api/v1/admin/wallets/**`

## 入口（controller 路径，不带 /api）

- `/business/members`（GET 列表 / POST 建档）、`/business/members/{id}/points`、`/business/members/{id}/wallet`
- `/business/members/{id}/im-binding`（PUT 绑定/改正 IM 账号，冲突 409 `IM_ACCOUNT_ALREADY_BOUND`）
- `/business/members/name-index/rebuild`（POST 人工触发姓名盲索引回填，权限 `member.pii.view`；另有定时任务
  `MemberNameIndexRebuildJob`，`member.name-index.rebuild-enabled` / `rebuild-ms`，默认 30 分钟一轮）
- `/admin/wallets/recharge`、`/admin/wallets/refund`
- `/internal/customer/wallets/*`、`/internal/customer/points/*`（内部扣减 / 释放 / 查询）

## 关键检索口径（客户列表 `/business/members` 的 `keyword`）

一个输入框同时支持四类：① 客户号 / 客户姓名的**包含**（走 `cst_member_name_token` 盲索引，密文姓名不可 LIKE）
② 手机号（纯数字按 SHA-256 摘要精确匹配）③ IM 账号 / IM 用户名（LIKE）。候选集拼进同一条 WHERE，
因此分页与 `total` 口径一致；盲索引未命中的存量客户由回填任务补齐（回填前仍可按客户号 LIKE 搜到）。

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_customer`
- Redis：无
- MQ：无

## 关键指标

- 钱包 / 积分扣减与释放吞吐
- 余额不足拒绝次数
- 客户建档 / 查询错误率
- 姓名盲索引回填进度（每轮 scanned / indexed / failed）

## 告警

- 扣减 / 释放失败（支付链路资金一致性风险）
- 余额不足异常占比升高（业务或数据异常）
- 账本追加失败或重复入账
- 同一租户出现大量「同一 account_id 多条客户」（去重防线被绕过）

## 关联 ID

- `X-Tenant-Context`：租户上下文（`tenantId` / `storeId`）
- `customerId`：客户主键（历史文案里的「会员」即此）
- `im_account`：IM 登录标识（如 `im_71` / openId），客户与 IM 用户的绑定落点
- `walletId` / 账本流水号：资金与积分流水

## 故障处置

- 扣减失败：核对 `gv_saas` 账本表与事务回滚（同库同事务要求）
- 余额异常：核对账本追加与冲正记录
- 充值 / 退款失败：核对钱包写路径与幂等

## 状态

- 骨架已落库（E6 前置）：会员 / 钱包 / 积分接口已实现；储值 / 积分余额校验在 E6 补齐（见 `SAAS_PLATFORM_07_EXECUTION.md`）
