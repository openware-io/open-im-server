-- 用户域手机号长度调整；依赖已执行的 V1 基线。
ALTER TABLE `user`
  MODIFY COLUMN `phone` varchar(30) DEFAULT NULL COMMENT '手机号';
