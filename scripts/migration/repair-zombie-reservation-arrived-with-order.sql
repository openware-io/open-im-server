-- =============================================================================
-- 修复：预约停在校「ARRIVED」但 order_id 已指向**终结**订单（僵尸预约占住包厢）
--
-- 症状：包厢收银台把该包厢显示成「客户已到店 / 已预订」，只给「到店开台」；
--       点它得到 HTTP 200（静默返回那条已 COMPLETED 的旧单）却不改变任何状态，
--       同时 cancel / no-show 都被状态守卫 409 —— 包厢永久不可开台。
--
-- 成因：ReservationApplicationService#doOpenTable 把「order_id 非空且订单存在」当作已开台，
--       在状态校验之前短路返回旧单，从不把预约推进到 CONVERTED。
--       这类行由历史构建写入，当前代码既产生不出、也治不好。
--
-- 口径：预约确实兑现成了订单（订单已 COMPLETED/有实收），终态应为 CONVERTED（已开台），
--       不是 CANCELLED / NO_SHOW；arrived_at 用会话开台时间回填（原值 NULL 本身不自洽）。
--
-- 影响面：ACK dev 实测全库只有 2 行（门店 100 / 包厢 K01），除 K01 外无第二间房被占。
-- 幂等：重跑不会重复改写（WHERE 已排除 CONVERTED）。
-- 回滚：无原地回滚；如需回退，按备份表/备份查询结果把 status/arrived_at/version 写回。
--
-- 执行前务必先跑第 1 段并用其结果决定第 2 段的范围。
-- =============================================================================

-- ----------------------------------------------------------------------------
-- 1) 影响范围（先看，不改）
-- ----------------------------------------------------------------------------
SELECT r.id,
       r.reservation_no,
       r.store_id,
       r.resource_id,
       r.status,
       r.order_id,
       r.arrived_at,
       o.order_no,
       o.status          AS order_status,
       s.id              AS session_id,
       s.status          AS session_status,
       s.opened_at
FROM ord_reservation r
LEFT JOIN ord_order        o ON o.id = r.order_id
LEFT JOIN ord_ktv_session  s ON s.order_id = r.order_id
WHERE r.order_id IS NOT NULL
  AND r.status <> 'CONVERTED';

-- ----------------------------------------------------------------------------
-- 2) 修复：置终态 CONVERTED + 回填 arrived_at
--    `AND r.order_id IS NOT NULL` 与第 1 段同谓词；生产环境如确认范围更大，
--    去掉 id 白名单即可（但请先留好第 1 段的输出作为 before 快照）。
-- ----------------------------------------------------------------------------
UPDATE ord_reservation r
LEFT JOIN ord_ktv_session s ON s.order_id = r.order_id
SET r.status     = 'CONVERTED',
    r.arrived_at = COALESCE(r.arrived_at, s.opened_at),
    r.version    = r.version + 1,
    r.updated_at = NOW(3)
WHERE r.order_id IS NOT NULL
  AND r.status <> 'CONVERTED'
  AND r.id IN (16, 17);

-- ----------------------------------------------------------------------------
-- 3) 修复后核对：应为空集
-- ----------------------------------------------------------------------------
SELECT r.id, r.reservation_no, r.status, r.order_id, r.arrived_at
FROM ord_reservation r
WHERE r.order_id IS NOT NULL
  AND r.status <> 'CONVERTED';

-- ----------------------------------------------------------------------------
-- 4) 该包厢是否已无「占位」的预约（应只剩 PENDING/CONFIRMED/ARRIVED 的无关行）
--    收银台 ACTIVE_RESERVATION_STATUSES = {PENDING, CONFIRMED, ARRIVED}
-- ----------------------------------------------------------------------------
SELECT r.id, r.reservation_no, r.resource_id, r.status, r.order_id, r.start_at, r.end_at
FROM ord_reservation r
WHERE r.status IN ('PENDING', 'CONFIRMED', 'ARRIVED')
  AND r.resource_id IS NOT NULL
ORDER BY r.resource_id, r.start_at;
