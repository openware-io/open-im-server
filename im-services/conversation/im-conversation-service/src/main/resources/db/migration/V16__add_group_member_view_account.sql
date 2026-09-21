-- 群级隐私：群主可关闭「允许群成员查看他人账号」（默认允许查看）。
-- 关闭后：非群主查询群成员列表/资料时，成员「账号（username）」对他人脱敏隐藏；群主与成员本人仍可见。
ALTER TABLE `conversation_group`
  ADD COLUMN `allow_member_view_account` BIT NOT NULL DEFAULT b'1' COMMENT '允许群成员查看他人账号';
