ALTER TABLE saa_admin_account
    ADD COLUMN im_account VARCHAR(64) NULL AFTER display_name,
    ADD UNIQUE KEY uk_saa_admin_account_im_account (im_account);
