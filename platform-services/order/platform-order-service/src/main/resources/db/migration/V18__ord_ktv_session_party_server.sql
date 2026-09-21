-- 开台会话补齐「人数」与「服务人员」快照：后台前端此前把「服务人员/人数」标为「未接入」。
--
-- party_size   到店人数（开台请求可带，不传保持 NULL = 未登记）；开台时按包厢容量校验（>0 且 <= 容量上限）。
-- server_id    服务人员资源ID快照（res_resource KTV_SERVER），点服务人员（创建/开始 ord_ktv_server_session）时回写本会话。
-- server_name  服务人员名称快照；资源服务不可达时为 NULL，调用方退回显示「服务人员#<id>」。
--
-- 冗余到会话是刻意的：订单列表与房态看板要展示「本包厢的服务人员/人数」，若每次读都跨域回查
-- ord_ktv_server_session + 资源服务，列表就会 N+1 且依赖外部可用性；快照在写入时刻固化，读路径零依赖。
-- 只加列，不改历史迁移、不 DROP；存量会话三列为 NULL（前端按「未设置」展示）。
ALTER TABLE `ord_ktv_session`
  ADD COLUMN `party_size` int NULL COMMENT '到店人数（开台登记，NULL=未登记）' AFTER `room_code_snapshot`,
  ADD COLUMN `server_id` bigint unsigned NULL COMMENT '服务人员资源ID快照' AFTER `party_size`,
  ADD COLUMN `server_name` varchar(64) NULL COMMENT '服务人员名称快照（资源服务不可达时为 NULL）' AFTER `server_id`;
