-- 群级隐私：群主可禁止群成员互加好友（默认允许）。
-- 关闭后：群成员之间禁止发起好友申请，成员列表/消息气泡匿名化（不暴露用户名与头像）。
ALTER TABLE `conversation_group`
  ADD COLUMN `allow_member_friend_request` BIT NOT NULL DEFAULT b'1' COMMENT '允许群成员互加好友';
