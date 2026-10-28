-- The demo tenant administrator is OpenChat-branded; keep the password unchanged.
UPDATE cons_admin_account
SET username = 'openchat-admin',
    display_name = 'OpenChat 管理员'
WHERE username = 'a380-admin';
