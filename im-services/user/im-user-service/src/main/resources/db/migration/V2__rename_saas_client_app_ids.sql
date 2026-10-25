-- Rename legacy brand-specific SaaS client IDs to generic client IDs.
UPDATE user_oidc_redirect_uri SET client_id = 'saas-c' WHERE client_id = 'saas-a380-c';
UPDATE user_oidc_redirect_uri SET client_id = 'saas-b' WHERE client_id = 'saas-a380-h5';
UPDATE user_oidc_scope SET client_id = 'saas-c' WHERE client_id = 'saas-a380-c';
UPDATE user_oidc_scope SET client_id = 'saas-b' WHERE client_id = 'saas-a380-h5';
UPDATE open_application SET app_id = 'saas-c' WHERE app_id = 'saas-a380-c';
UPDATE open_application SET app_id = 'saas-b' WHERE app_id = 'saas-a380-h5';
UPDATE user_oidc_client SET client_id = 'saas-c' WHERE client_id = 'saas-a380-c';
UPDATE user_oidc_client SET client_id = 'saas-b' WHERE client_id = 'saas-a380-h5';
