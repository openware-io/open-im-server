-- 回填 cst_member 的 IM 关联三列（im_account / im_username / im_bound_at）。
--
-- 背景：V4 加这三列就是为了记「这个客户是哪个 IM 用户」，但**从来没有回填过**，于是
-- 后台「客户管理」的 IM 账号 / IM 用户名列对所有人都显示「—」，运营误以为这些客户都没关联 IM，
-- 甚至据此判断数据是垃圾。真实关联一直在统一账号模型里：
--   idt_login_identity(account_id, login_type='IM', login_identifier)  ← IM 登录标识
--   idt_profile_sync_record(account_id, profile_json.nickname)          ← IM 昵称快照
--
-- 口径（与创建时回填、与后台展示完全一致）：
--   1) 只认 status='ACTIVE' 的 IM 身份；账号没有 IM 身份 → 三列保持 NULL（**不伪造**）；
--   2) 昵称取最新一条 profile 快照；快照缺失只回填 IM 标识；
--   3) 只动 im_account IS NULL 的行（幂等、可重入）；
--   4) 跳过 im_account 已被别的客户占用的（(tenant_id, im_account) 有唯一索引，否则整条 UPDATE 会失败）；
--   5) 员工账号上的客户档案（员工也可以是消费者）同样回填——口径只取决于账号有没有 IM 身份。
--
-- 回滚：无需回滚（只补展示字段，不改变任何业务判定；需要时可把三列置回 NULL）。

UPDATE cst_member m
  JOIN (
    SELECT li.account_id,
           MIN(li.login_identifier) AS im_account,
           MIN(li.created_at) AS bound_at,
           (SELECT JSON_UNQUOTE(JSON_EXTRACT(p.profile_json, '$.nickname'))
              FROM idt_profile_sync_record p
             WHERE p.account_id = li.account_id
             ORDER BY p.id DESC
             LIMIT 1) AS nickname
      FROM idt_login_identity li
     WHERE li.login_type = 'IM'
       AND li.status = 'ACTIVE'
     GROUP BY li.account_id
  ) im ON im.account_id = m.account_id
  LEFT JOIN (
    SELECT tenant_id, im_account FROM cst_member WHERE im_account IS NOT NULL
  ) taken ON taken.tenant_id = m.tenant_id AND taken.im_account = im.im_account
   SET m.im_account = im.im_account,
       m.im_username = COALESCE(m.im_username, im.nickname),
       m.im_bound_at = COALESCE(m.im_bound_at, im.bound_at)
 WHERE m.im_account IS NULL
   AND m.account_id IS NOT NULL
   AND taken.im_account IS NULL;
