-- 储值账户懒初始化（2026-09-19）：清理历史「无用储值账户」。
--
-- 背景：此前读路径（GET /business/members/me、GET /me/wallet、后台按会员查余额）都会
-- 调用 ensureAccount 懒创建账户，于是**任何被访问过的会员**都留下一条
-- available_amount = 0 / frozen_amount = 0、且没有任何账本流水的空账户：
-- 既没有储值，也不代表任何财务事实，只会让「储值管理」列表显示一堆空账户，
-- 并让「没有储值的会员」看起来像是有账户。
--
-- 变更后：储值账户只在**首次充值**（POST /admin/wallets/recharge，账本 RECHARGE）时创建；
-- 读路径对没有账户的会员返回零额只读视图（不落库、不报错）。
--
-- 安全边界（只删「绝对无用」的账户）：
--   1) 可用余额为 0 且冻结余额为 0；
--   2) 该账户没有任何账本流水（LEFT JOIN 无命中）——有过充值/消费/退还/释放流水的账户一律保留，
--      哪怕余额已经归零：流水是财务事实，不允许因为余额为 0 就删。
-- 因此本迁移不会影响任何真实持有过储值的会员。
DELETE a FROM `cst_wallet_account` a
  LEFT JOIN `cst_wallet_ledger` l ON l.wallet_account_id = a.id
 WHERE a.available_amount = 0
   AND a.frozen_amount = 0
   AND l.id IS NULL;
