-- 下线 IM 侧 coin/points：删除已无代码引用的代币/积分账户与流水表（A380币/积分已迁移至 SaaS cst_wallet_*/cst_point_*）。
DROP TABLE IF EXISTS `user_coin_account`;
DROP TABLE IF EXISTS `user_coin_ledger`;
DROP TABLE IF EXISTS `user_point_account`;
DROP TABLE IF EXISTS `user_point_ledger`;
