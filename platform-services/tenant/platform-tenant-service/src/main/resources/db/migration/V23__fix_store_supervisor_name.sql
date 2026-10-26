-- store.supervisor was created through an ASCII-only import path and its name was
-- stored as four literal question marks. Repair the corrupted display name only.
UPDATE iam_role
SET name = '门店主管'
WHERE code = 'store.supervisor'
  AND (name = '????' OR name IS NULL);
