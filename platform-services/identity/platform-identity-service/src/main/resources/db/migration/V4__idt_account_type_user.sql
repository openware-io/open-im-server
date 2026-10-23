-- Domain: identity; incremental migration.
-- 账号类型收敛：员工/客户由 tnt_membership 决定，全局账号只保留 USER / PLATFORM_OPERATOR。
UPDATE `idt_account`
SET `account_type` = 'USER'
WHERE `account_type` IN ('EMPLOYEE', 'CUSTOMER');

ALTER TABLE `idt_account`
  MODIFY COLUMN `account_type` varchar(32) NOT NULL DEFAULT 'USER'
    COMMENT '账号类型 PLATFORM_OPERATOR/USER';
