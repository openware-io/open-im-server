-- 客户（cst_member）关联 IM 账号：IM 登录标识 / IM 用户名快照 / 绑定时间。
--
-- 背景：「会员」这套等级/权益/成长值业务并不存在，cst_member 实际存的是**客户**。客户此前只能按
-- account_id（SaaS 账号 idt_account.id）关联；C 端 IM 用户（如 im_71 / openId）没有任何落点，
-- 于是同一个 IM 用户会被反复建档成多条客户（ACK 租户 100 实测 63 行客户只有 23 个不同 account_id）。
-- 本迁移只补三个列，让「客户 ↔ IM 账号」有可落库、可检索、可校验唯一性的绑定落点。
--
-- 顺序约定（任务书要求「去重 → 清理 → 建唯一索引」）：
--   V4（本文件）：加列               —— 不改任何既有行，全部为 NULL（= 未绑定）
--   V5          ：建姓名盲索引表     —— 去重/清理要同步删 token 行，故必须先建表
--   V6          ：去重 → 清理 → 建索引（此时才允许出现 (tenant_id, account_id)/(tenant_id, im_account) 上的索引）
--
-- 幂等性：Flyway 只执行一次；MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重跑前需人工确认（本迁移不写数据，
-- 空库/空表上执行结果与「无操作」等价）。
ALTER TABLE `cst_member`
  ADD COLUMN `im_account` varchar(64) NULL COMMENT 'IM 登录标识(如 im_71 / openId)' AFTER `account_id`,
  ADD COLUMN `im_username` varchar(128) NULL COMMENT 'IM 用户名/昵称快照' AFTER `im_account`,
  ADD COLUMN `im_bound_at` datetime(3) NULL COMMENT 'IM 绑定时间' AFTER `im_username`;
