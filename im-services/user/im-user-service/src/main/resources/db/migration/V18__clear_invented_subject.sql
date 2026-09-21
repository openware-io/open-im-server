-- 修正 V17 误回填的编造主体名（"星空AI（自营）"）。
-- 主体名应由业务方/申请方提供，不预设编造值；此处撤销该回填，保证新环境也不残留。
UPDATE `open_application` SET `subject_name` = NULL
WHERE `app_type` = 'FIRST_PARTY' AND `subject_name` = '星空AI（自营）';
