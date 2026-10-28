-- Local demo administrator credentials: keep the documented Admin@123 password.
-- The original V1 seed used a one-off BCrypt hash whose plaintext was not recorded.
-- This migration only resets the two demo accounts; real environments use SSO/account management.
UPDATE `cons_admin_account`
   SET `password_hash` = '$2a$10$mQBZ8uZD4eCxaS0htyE3EOQWnQ38BKLCt1GLiA78aUrKLZ5McOZ5W',
       `updated_at` = NOW(3)
 WHERE `username` IN ('admin', 'tenant-admin');
