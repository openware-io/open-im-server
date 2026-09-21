UPDATE iam_user_role
SET authorization_version = 1,
    updated_at = NOW(3)
WHERE status = 'ACTIVE'
  AND authorization_version <= 0;
