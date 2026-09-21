-- 预约联系人（姓名 + 手机号，C 端 H5 提交后落库）
ALTER TABLE ord_reservation ADD COLUMN contact varchar(128) NULL COMMENT '预订联系人（姓名 手机号）' AFTER party_size;
