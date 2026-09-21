-- 预约改为「预约房型」（到店分配具体包厢）：ord_reservation 新增 room_type_id，并收敛 resource_id 语义。
-- 规格：docs/renovation/KTV_RESERVATION_ROOM_TYPE.md §2。
--
-- 决策（历史行不回填、新数据房型驱动）：
--   1) 预约对象 = 房型（res_room_type.id，同门店）；创建预约只写 room_type_id，不写 resource_id。
--   2) resource_id 语义收敛为「到店分配后的实际包厢」：由 POST /admin/reservations/{id}/assign-room 写入；
--      历史行里的 resource_id 仍是「下单时直接选定的包厢」，读取时按旧数据处理
--      （列表/详情回退展示旧包厢名，开台继续用既有 resource_id）。
--   3) **历史行不回填 room_type_id**：房型字典在资源域（res_room_type），与本迁移不在同一库/服务，
--      迁移里没有可靠的关联依据（历史 resource_id 可能已被改派/停用，跨服务也读不到）；
--      按规格 §2 明确「不回填」，宁可让历史行保持 room_type_id 为 NULL，由读路径与开台路径按旧语义兼容。
--      因此本迁移不引入任何默认房型：NULL 只表示「房型未知（历史预约）」。
--
-- 三种组合的语义（读路径与开台路径都按此判定）：
--   room_type_id NULL     + resource_id NOT NULL = 历史预约（仅包厢驱动，仍可列表展示与开台）；
--   room_type_id NOT NULL + resource_id NULL     = 新预约（房型驱动，待到店分配包厢）；
--   room_type_id NOT NULL + resource_id NOT NULL = 新预约且已分配包厢。
ALTER TABLE `ord_reservation`
  ADD COLUMN `room_type_id` bigint unsigned NULL
    COMMENT '预约房型ID（res_room_type.id，同门店）；创建预约（按房型）时写入；NULL=历史预约（仅 resource_id 驱动）'
    AFTER `resource_id`,
  ADD KEY `idx_ord_reservation_room_type` (`tenant_id`, `store_id`, `room_type_id`, `start_at`, `status`);

-- resource_id 语义变更：从「预约时选定的包厢」改为「到店分配后的实际包厢」。
-- 已执行的 V2__ord_reservation.sql 不可改（Flyway 校验和不可变），因此用 MODIFY COLUMN 只更新注释与语义，
-- 不改类型与可空性（仍是 bigint unsigned NULL）。
ALTER TABLE `ord_reservation`
  MODIFY COLUMN `resource_id` bigint unsigned NULL
    COMMENT '实际使用包厢ID（res_resource.id）；预约创建时不写，到店分配包厢（assign-room）时写入；历史行=创建预约时直接选定的包厢';
