-- Rename the demo tenant administrator's password login identifiers.
UPDATE idt_login_identity
SET login_identifier = 'openchat-admin'
WHERE login_identifier = 'a380-admin';
