-- Rename the SaaS tenant administrator login identifier.
UPDATE idt_login_identity
SET login_identifier = 'tenant-admin'
WHERE login_identifier = 'openchat-admin';
