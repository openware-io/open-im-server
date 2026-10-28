-- Rename legacy brand-specific consumer app IDs to generic client IDs.
UPDATE iam_consumer_application SET app_id = 'saas-c' WHERE app_id = 'saas-a380-c';
UPDATE iam_consumer_application SET app_id = 'saas-b' WHERE app_id = 'saas-a380-h5';
UPDATE tnt_app_user_authorization SET app_id = 'saas-c' WHERE app_id = 'saas-a380-c';
UPDATE tnt_app_user_authorization SET app_id = 'saas-b' WHERE app_id = 'saas-a380-h5';
