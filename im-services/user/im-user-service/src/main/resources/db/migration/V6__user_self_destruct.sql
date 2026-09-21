-- 账号自毁策略：用户可设置长期不登录自动硬删（off/1mo/3mo/6mo/1yr）。
-- self_destruct_at 为到期硬删的截止时间（UTC）；last_login_at 记录最近登录（登录即续期）。

ALTER TABLE `user`
  ADD COLUMN `self_destruct_policy` varchar(16) NOT NULL DEFAULT 'off' COMMENT '自毁策略 off/1mo/3mo/6mo/1yr' AFTER `status`,
  ADD COLUMN `self_destruct_at` datetime(3) DEFAULT NULL COMMENT '自毁截止时间（到期硬删账号与数据）' AFTER `self_destruct_policy`,
  ADD COLUMN `last_login_at` datetime(3) DEFAULT NULL COMMENT '最近登录时间' AFTER `self_destruct_at`,
  ADD KEY `idx_user_self_destruct_at` (`self_destruct_at`);
