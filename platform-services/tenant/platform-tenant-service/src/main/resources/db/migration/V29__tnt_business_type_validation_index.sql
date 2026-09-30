-- P4B-1：门店业态写路径使用 ACTIVE 字典校验。
-- 业态编码仍由 tnt_business_type 维护；本索引支持 ACTIVE 字典查询，不复制业态数据到其他域。
ALTER TABLE `tnt_business_type`
  ADD KEY `idx_tnt_business_type_status_code` (`status`, `code`);
