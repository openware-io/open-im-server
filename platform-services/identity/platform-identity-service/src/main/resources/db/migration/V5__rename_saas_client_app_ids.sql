-- Rename legacy brand-specific OAuth link app IDs to generic client IDs.
UPDATE idt_oauth_link SET app_id = 'saas-c' WHERE app_id = 'saas-a380-c';
UPDATE idt_oauth_link SET app_id = 'saas-b' WHERE app_id = 'saas-a380-h5';
