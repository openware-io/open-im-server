-- 会话域群组成员邀请开关：默认允许普通成员邀请。
ALTER TABLE `conversation_group`
  ADD COLUMN `allow_member_invite` BIT NOT NULL DEFAULT b'1' COMMENT '是否允许普通成员邀请用户';
