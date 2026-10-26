-- HOTEL is a real business type but the baseline dictionary only seeded KTV.
INSERT INTO tnt_business_type (code, name, status, created_by, created_at, updated_by, updated_at)
SELECT 'HOTEL', '酒店', 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM tnt_business_type WHERE code = 'HOTEL');

-- HOTEL2/HOTEL3 were test placeholders, never valid business types.
DELETE FROM tnt_business_entitlement
WHERE tenant_id = 100
  AND business_type IN ('HOTEL2', 'HOTEL3');
