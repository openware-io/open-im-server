-- 全局禁言（muted）：用户可登录、可读消息，但全局禁言期间不能发送消息（区别于 disabled 封禁）。

ALTER TABLE `user`
  MODIFY COLUMN `status` enum('active','muted','disabled') NOT NULL DEFAULT 'active' COMMENT '账号状态(active/muted/disabled)';
