-- P7-B：计价方案是租户经营配置，不是平台订阅方案。
UPDATE iam_menu
SET path = '/admin/ktv/config', scope_level = 'TENANT', parent_id = 0, sort_no = 45,
    name = 'KTV 配置', icon = 'setting', domain_code = 'ktv'
WHERE code = 'pricing';
