package io.openware.platform.admin.infra.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 报表只读聚合 Mapper：直接聚合现有业务表（ord_order/ord_order_item/ord_ktv_session/
 * ord_ktv_server_session/ord_inventory_transaction/ord_inventory_stock/pay_intent/pay_transaction/
 * pay_refund/res_resource/tnt_store），
 * 不建领域表、不写 Flyway。租户边界通过显式 tenant_id 过滤，日期范围 [from, to)。
 *
 * <p><b>营业日（门店本地日 + 营业日切点）</b>：业务时间列落库的是 UTC 墙钟（容器 {@code TZ=UTC}），
 * 门店按本地时区 + 营业日切点经营（KTV 通宵场次归前一营业日），口径见
 * {@code io.openware.infrastructure.time.ReportTimeBuckets}。因此本 Mapper 里**所有**切日表达式都是
 * <pre>{@code DATE_FORMAT(DATE_ADD(<时间列>, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')}</pre>
 * 其中 {@code businessDayShiftSeconds} 由调用方传入
 * {@code ReportTimeBuckets.BUSINESS_DAY_SHIFT_SECONDS}（时区偏移 − 营业日切点），
 * {@code from}/{@code to} 也由 {@code ReportTimeBuckets.windowFrom/windowToExclusive} 换算成营业日边界，
 * 两边严格对齐（不会出现首尾半天、也不会多出一个不存在的桶）。
 * <b>偏移量只有那一处定义</b>，由 {@code ReportMapperBusinessDayContractTest} 读注解 SQL 守住。
 *
 * <p><b>为什么 GROUP BY 用别名 {@code business_date} 而不是重复整个表达式</b>：这个表达式带占位符，
 * 重复一遍会生成两个不同的参数位置，MySQL 8 的 {@code ONLY_FULL_GROUP_BY} 无法判定两者等价，
 * 会直接报 1055（已实测）。{@code business_date} 在本 Mapper 涉及的表里都不是真实列名，用别名不歧义。
 *
 * <p><b>周/月/年不在这里做</b>：Mapper 只按**营业日**出一行（这是所有粒度都能无损上卷的最小单位），
 * 日 → 周/月/年的分桶由 {@code ReportTimeBuckets.bucket} 统一归一化（服务端唯一实现），
 * 因此不存在「每个报表各写一套周/月算法」。
 *
 * <p><b>币种维度（规范 §3.6 / 影响面清单 §5.4）</b>：凡是对金额做 SUM 的聚合，**必须**把币种
 * 作为分组键并把 {@code currency_code} 一起返回，禁止跨币种静默求和。币种取该笔金额所在记录的
 * 快照列：订单口径取 {@code ord_order.currency_code}（退款亦回查订单，
 * {@code pay_refund.currency_code} 是 {@code V10__pay_currency_snapshot.sql} 新增并对历史行
 * 缺省回填 USD 的列，用它会把历史退款错记成 USD；也不能按当前租户设置解释历史退款）。
 */
@Mapper
public interface ReportMapper {

    @Select("""
            SELECT o.created_by AS employee_id, o.store_id,
                   o.currency_code AS currency_code,
                   DATE_FORMAT(DATE_ADD(o.created_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   COUNT(*) AS open_order_count
            FROM ord_order o
            WHERE o.tenant_id = #{tenantId}
              AND o.status NOT IN ('CANCELLED','VOIDED')
              AND o.created_by <> 0
              AND o.created_at >= #{from} AND o.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
            GROUP BY o.created_by, o.store_id, o.currency_code, business_date
            """)
    List<Map<String, Object>> selectCashierOpenOrders(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    @Select("""
            SELECT pi.created_by AS employee_id, pi.store_id,
                   t.currency_code AS currency_code,
                   DATE_FORMAT(DATE_ADD(t.occurred_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   SUM(t.amount) AS collected_amount
            FROM pay_transaction t
            JOIN pay_intent pi ON pi.id = t.payment_intent_id
            WHERE t.tenant_id = #{tenantId}
              AND t.status = 'SUCCEEDED'
              AND pi.created_by <> 0
              AND t.occurred_at >= #{from} AND t.occurred_at < #{to}
              AND (#{storeId} IS NULL OR pi.store_id = #{storeId})
            GROUP BY pi.created_by, pi.store_id, t.currency_code, business_date
            """)
    List<Map<String, Object>> selectCashierCollections(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    @Select("""
            SELECT i.created_by AS employee_id, o.store_id,
                   o.currency_code AS currency_code,
                   DATE_FORMAT(DATE_ADD(i.created_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   COUNT(*) AS add_on_count
            FROM ord_order_item i
            JOIN ord_order o ON o.id = i.order_id AND o.tenant_id = i.tenant_id
            WHERE i.tenant_id = #{tenantId}
              AND i.item_type = 'ADD_ON'
              AND i.created_by <> 0
              AND i.created_at >= #{from} AND i.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
            GROUP BY i.created_by, o.store_id, o.currency_code, business_date
            """)
    List<Map<String, Object>> selectCashierAddOns(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    @Select("""
            SELECT ks.server_resource_id AS employee_id,
                   r.name AS employee_name,
                   o.store_id AS store_id,
                   o.currency_code AS currency_code,
                   DATE_FORMAT(DATE_ADD(ks.created_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   SUM(ks.duration_seconds) AS service_seconds,
                   COUNT(*) AS add_on_count
            FROM ord_ktv_server_session ks
            JOIN res_resource r ON r.id = ks.server_resource_id
            LEFT JOIN ord_order o ON o.id = ks.order_id AND o.tenant_id = ks.tenant_id
            WHERE ks.tenant_id = #{tenantId}
              AND ks.status <> 'CANCELLED'
              AND ks.created_at >= #{from} AND ks.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
            GROUP BY ks.server_resource_id, r.name, o.store_id, o.currency_code, business_date
            """)
    List<Map<String, Object>> selectServerPerformance(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    @Select("""
            SELECT o.store_id AS store_id,
                   s.name AS store_name,
                   DATE_FORMAT(DATE_ADD(o.created_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   o.currency_code AS currency_code,
                   COUNT(*) AS order_count,
                   SUM(o.total_amount) AS receivable_amount,
                   SUM(o.paid_amount) AS paid_amount,
                   SUM(o.discount_amount) AS discount_amount
            FROM ord_order o
            LEFT JOIN tnt_store s ON s.id = o.store_id AND s.tenant_id = o.tenant_id
            WHERE o.tenant_id = #{tenantId}
              AND o.status NOT IN ('CANCELLED','VOIDED')
              AND o.created_at >= #{from} AND o.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
            GROUP BY o.store_id, s.name, business_date, o.currency_code
            """)
    List<Map<String, Object>> selectOperations(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    /**
     * 退款聚合（经营报表/支付报表/销售报表共用）。
     *
     * <p><b>状态口径</b>：只统计 **REFUNDED**（款项已登记退还的终态）。
     * 依据 {@code RefundApplicationService} 的状态机（{@code STATUS_PENDING/APPROVED/REJECTED/REFUNDED}，
     * 见 {@code common-payment-service RefundApplicationService.java:25-28} 与
     * {@code V7__pay_refund_status_pending.sql}）：{@code pay_refund.status} 只会有这四个值，
     * **不存在 SUCCEEDED**（那是 {@code pay_intent/pay_transaction} 的收款态）。历史实现写成
     * {@code r.status = 'SUCCEEDED'} 使退款口径恒为空行，本处按真实终态修正。
     * PENDING（待审批）/ APPROVED（审批通过但未登记退款）/ REJECTED（已驳回）都不计入：
     * 报表统计的是已发生的资金退还，未退款成功的审批单不能冲减实收。
     *
     * <p><b>金额</b>：优先 {@code approved_amount}（审批金额）；
     * {@code RefundApplicationService#markRefunded} 只在 APPROVED 之后调用，因此 REFUNDED 行的
     * approved_amount 必定已写入，{@code COALESCE} 仅兜底历史脏行，避免 SUM 因 NULL 整行为 NULL。
     *
     * <p><b>币种</b>：取原订单快照 {@code ord_order.currency_code}。{@code pay_refund} 自
     * {@code V10__pay_currency_snapshot.sql:22-24} 起也有 currency_code，但该列是对历史行的
     * 缺省回填（USD），用订单快照才能保证历史退款按真实原币种归集。
     */
    @Select("""
            SELECT o.store_id AS store_id,
                   s.name AS store_name,
                   DATE_FORMAT(DATE_ADD(r.created_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   o.currency_code AS currency_code,
                   COUNT(*) AS refund_count,
                   SUM(COALESCE(r.approved_amount, 0)) AS refund_amount
            FROM pay_refund r
            JOIN ord_order o ON o.id = r.order_id AND o.tenant_id = r.tenant_id
            LEFT JOIN tnt_store s ON s.id = o.store_id AND s.tenant_id = o.tenant_id
            WHERE r.tenant_id = #{tenantId}
              AND r.status = 'REFUNDED'
              AND r.created_at >= #{from} AND r.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
            GROUP BY o.store_id, s.name, business_date, o.currency_code
            """)
    List<Map<String, Object>> selectRefunds(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    @Select("""
            SELECT pi.store_id AS store_id,
                   s.name AS store_name,
                   DATE_FORMAT(DATE_ADD(t.occurred_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   t.currency_code AS currency_code,
                   COUNT(*) AS collection_count,
                   SUM(t.amount) AS collected_amount
            FROM pay_transaction t
            JOIN pay_intent pi ON pi.id = t.payment_intent_id
            LEFT JOIN tnt_store s ON s.id = pi.store_id AND s.tenant_id = pi.tenant_id
            WHERE t.tenant_id = #{tenantId}
              AND t.status = 'SUCCEEDED'
              AND t.occurred_at >= #{from} AND t.occurred_at < #{to}
              AND (#{storeId} IS NULL OR pi.store_id = #{storeId})
            GROUP BY pi.store_id, s.name, business_date, t.currency_code
            """)
    List<Map<String, Object>> selectCollections(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    /**
     * 资源利用率（KTV 包厢）——**按「每次开台」一行**，时长取该次会话自己的
     * {@code ord_ktv_session} 窗口，**不再用 {@code res_occupation} 的占用窗口**。
     *
     * <p><b>为什么换数据源（历史缺陷，已在 ACK 环境实测）</b>：开台时 {@code res_occupation}
     * 写的是 {@code occupy(now, now + 24h)} 的**固定 24 小时窗口**，结台只把 {@code status}
     * 改成 RELEASED、不回缩 {@code end_at}，所以每一行占用都恰好是 24h。旧实现按
     * (门店, 包厢, 营业日) 把占用窗口求和，同一包厢多开一次台就多算 24h：实测包厢 1015 开出 4 条占用
     * = 96h、1017 开出 2 条 = 48h，与真实消费时长（1017 实际 336 分 + 4 分）毫无关系。
     * 真实时长只有会话表有：{@code opened_at → closed_at} 再扣掉暂停 {@code paused_seconds}。
     *
     * <p><b>口径（响应字段注释与测试都按此钉住，不得改回占用窗口）</b>：
     * <ul>
     *   <li><b>一行 = 一次开台</b>（一条 {@code ord_ktv_session}），因此**没有 GROUP BY**：
     *       同一包厢同一营业日开台多次就是多行，各行只算自己那一次的时长；</li>
     *   <li>{@code duration_seconds} = {@code TIMESTAMPDIFF(SECOND, opened_at, closed_at) - paused_seconds}；
     *       <b>尚未结台</b>（{@code closed_at IS NULL}）记 0，不拿「现在」当结束时间（否则每次查询数字都在变）；
     *       {@code GREATEST(..., 0)} 兜脏数据，避免出现负时长；</li>
     *   <li>{@code turnover_count} = 该次开台**是否已完成**（{@code status='CLOSED'} 记 1，否则 0）；
     *       同一包厢同一营业日各行相加 = 当日完成场次数（即翻台次数）——不再拿「RELEASED 占用行数」当翻台次数
     *       （那只是释放动作发生过几次，与结台无关）；</li>
     *   <li>营业日按**开台时间 {@code opened_at}** 归属（取数区间 {@code [from, to)} 与切日表达式同一列），
     *       跨零点通宵场次仍按 {@link io.openware.infrastructure.time.ReportTimeBuckets} 的切点归前一营业日；</li>
     *   <li>门店取订单 {@code ord_order.store_id}（会话表本身没有门店列），门店过滤与其它报表同一写法；
     *       已取消的会话（{@code status='CANCELLED'}，含整单取消/作废时的会话终结）不计入资源消费。</li>
     * </ul>
     */
    @Select("""
            SELECT ks.id AS session_id,
                   o.store_id AS store_id,
                   s.name AS store_name,
                   ks.room_resource_id AS resource_id,
                   r.name AS resource_name,
                   DATE_FORMAT(DATE_ADD(ks.opened_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   DATE_FORMAT(ks.opened_at, '%Y-%m-%dT%H:%i:%s') AS opened_at,
                   DATE_FORMAT(ks.closed_at, '%Y-%m-%dT%H:%i:%s') AS closed_at,
                   ks.status AS session_status,
                   CASE WHEN ks.closed_at IS NULL THEN 0
                        ELSE GREATEST(TIMESTAMPDIFF(SECOND, ks.opened_at, ks.closed_at)
                                      - COALESCE(ks.paused_seconds, 0), 0)
                   END AS duration_seconds,
                   CASE WHEN ks.status = 'CLOSED' THEN 1 ELSE 0 END AS turnover_count
            FROM ord_ktv_session ks
            JOIN ord_order o ON o.id = ks.order_id AND o.tenant_id = ks.tenant_id
            JOIN res_resource r ON r.id = ks.room_resource_id AND r.tenant_id = ks.tenant_id
            LEFT JOIN tnt_store s ON s.id = o.store_id AND s.tenant_id = o.tenant_id
            WHERE ks.tenant_id = #{tenantId}
              AND r.resource_type = 'KTV_ROOM'
              AND ks.status <> 'CANCELLED'
              AND ks.opened_at IS NOT NULL
              AND ks.opened_at >= #{from} AND ks.opened_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
            """)
    List<Map<String, Object>> selectResourceUtilization(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    /**
     * 库存成本与毛利报表 —— 期间**收入**侧：订单明细金额按 (门店, 营业日, 明细币种快照) 分组。
     *
     * <p>币种取 {@code ord_order_item.currency_code}（V22 明细落库时的快照，改租户设置不改历史），
     * 因此同一门店同一天的不同币种会分行返回，绝不相加成一个数字。
     * 明细状态限 ACTIVE、订单排除已取消/已作废，与经营报表口径一致。
     */
    @Select("""
            SELECT o.store_id AS store_id,
                   s.name AS store_name,
                   DATE_FORMAT(DATE_ADD(o.created_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   i.currency_code AS currency_code,
                   SUM(i.total_amount) AS revenue_amount
            FROM ord_order_item i
            JOIN ord_order o ON o.id = i.order_id AND o.tenant_id = i.tenant_id
            LEFT JOIN tnt_store s ON s.id = o.store_id AND s.tenant_id = o.tenant_id
            WHERE i.tenant_id = #{tenantId}
              AND i.status = 'ACTIVE'
              AND o.status NOT IN ('CANCELLED','VOIDED')
              AND o.created_at >= #{from} AND o.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
            GROUP BY o.store_id, s.name, business_date, i.currency_code
            """)
    List<Map<String, Object>> selectItemRevenue(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    /**
     * 库存成本与毛利报表 —— 期间**成本**侧：按 (门店, 营业日, 物料, 平均成本币种) 给出**净售出数量**，
     * 金额由应用层用「净售出数量 × 该物料移动加权平均成本」定点计算（不在 SQL 做浮点乘除）。
     *
     * <p>数量口径：{@code CONSUME}（加项消耗）+ {@code REVERSE}（作废回补，数量为负冲回）的
     * {@code -quantity_delta} 之和，即「期间净出库量」；{@code ADJUST_OUT}（盘亏）属于损耗、不计入毛利成本。
     * 单价口径：库存行**当前**的 {@code avg_cost}（期末平均成本，见 ReportController 的 costBasis 说明）。
     */
    @Select("""
            SELECT t.store_id AS store_id,
                   s.name AS store_name,
                   DATE_FORMAT(DATE_ADD(t.created_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   t.material_id AS material_id,
                   COALESCE(NULLIF(stk.currency_code, ''), 'USD') AS currency_code,
                   stk.avg_cost AS avg_cost,
                   SUM(CASE WHEN t.transaction_type IN ('CONSUME','REVERSE') THEN -t.quantity_delta ELSE 0 END)
                       AS net_quantity
            FROM ord_inventory_transaction t
            JOIN ord_inventory_stock stk
              ON stk.tenant_id = t.tenant_id AND stk.store_id = t.store_id AND stk.material_id = t.material_id
            LEFT JOIN tnt_store s ON s.id = t.store_id AND s.tenant_id = t.tenant_id
            WHERE t.tenant_id = #{tenantId}
              AND t.transaction_type IN ('CONSUME','REVERSE')
              AND t.created_at >= #{from} AND t.created_at < #{to}
              AND (#{storeId} IS NULL OR t.store_id = #{storeId})
            GROUP BY t.store_id, s.name, business_date, t.material_id,
                     COALESCE(NULLIF(stk.currency_code, ''), 'USD'), stk.avg_cost
            """)
    List<Map<String, Object>> selectInventoryConsumptionCost(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    // —— 销售报表（GET /admin/reports/sales）——

    /**
     * 销售报表**统计**侧（一）：销售单据汇总，按 (门店, 营业日, 币种) 一行。
     *
     * <p>口径与经营报表同源（{@code ord_order}）：
     * <ul>
     *   <li>{@code order_count} / {@code sales_amount} / {@code discount_amount} / {@code paid_amount}
     *       只累加**有效单据**（{@code status NOT IN ('CANCELLED','VOIDED')}），与
     *       {@link #selectOperations} 同一条件；</li>
     *   <li>{@code voided_count} / {@code voided_amount} 单独统计**已作废**单据
     *       （{@code status='VOIDED'}）的金额，用于核对「作废冲销」而不是并进销售额
     *       —— 作废不是收入，混进销售额会让毛利虚高；</li>
     *   <li>{@code CANCELLED}（未成立/已取消）既不计销售额也不计作废金额，避免把从未收款的单当成损失。</li>
     * </ul>
     * 金额一律取订单的**币种快照** {@code ord_order.currency_code}，跨币种分行返回。
     */
    @Select("""
            SELECT o.store_id AS store_id,
                   s.name AS store_name,
                   DATE_FORMAT(DATE_ADD(o.created_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   o.currency_code AS currency_code,
                   SUM(CASE WHEN o.status NOT IN ('CANCELLED','VOIDED') THEN 1 ELSE 0 END) AS order_count,
                   SUM(CASE WHEN o.status NOT IN ('CANCELLED','VOIDED') THEN o.total_amount ELSE 0 END)
                       AS sales_amount,
                   SUM(CASE WHEN o.status NOT IN ('CANCELLED','VOIDED') THEN o.discount_amount ELSE 0 END)
                       AS discount_amount,
                   SUM(CASE WHEN o.status NOT IN ('CANCELLED','VOIDED') THEN o.paid_amount ELSE 0 END)
                       AS paid_amount,
                   SUM(CASE WHEN o.status = 'VOIDED' THEN 1 ELSE 0 END) AS voided_count,
                   SUM(CASE WHEN o.status = 'VOIDED' THEN o.total_amount ELSE 0 END) AS voided_amount
            FROM ord_order o
            LEFT JOIN tnt_store s ON s.id = o.store_id AND s.tenant_id = o.tenant_id
            WHERE o.tenant_id = #{tenantId}
              AND o.created_at >= #{from} AND o.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
            GROUP BY o.store_id, s.name, business_date, o.currency_code
            """)
    List<Map<String, Object>> selectSalesOrders(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    /**
     * 销售报表**统计**侧（二）：收款按 (门店, 营业日, 币种, 支付方式) 一行，用于「现金/线上/储值/积分」构成。
     *
     * <p>支付方式取 {@code COALESCE(pay_transaction.provider, pay_intent.provider)}：
     * {@code pay_transaction.provider} 是 {@code V3__pay_transaction_provider.sql} 之后才写入的列，
     * 历史行是 NULL，此时回落到支付意图上的方式，避免历史交易被归到「其它」。
     * 只统计 {@code SUCCEEDED}（真正到账的流水），与支付报表口径一致。
     */
    @Select("""
            SELECT pi.store_id AS store_id,
                   s.name AS store_name,
                   DATE_FORMAT(DATE_ADD(t.occurred_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   t.currency_code AS currency_code,
                   COALESCE(t.provider, pi.provider) AS provider,
                   COUNT(*) AS payment_count,
                   SUM(t.amount) AS payment_amount
            FROM pay_transaction t
            JOIN pay_intent pi ON pi.id = t.payment_intent_id
            LEFT JOIN tnt_store s ON s.id = pi.store_id AND s.tenant_id = pi.tenant_id
            WHERE t.tenant_id = #{tenantId}
              AND t.status = 'SUCCEEDED'
              AND t.occurred_at >= #{from} AND t.occurred_at < #{to}
              AND (#{storeId} IS NULL OR pi.store_id = #{storeId})
            GROUP BY pi.store_id, s.name, business_date, t.currency_code,
                     COALESCE(t.provider, pi.provider)
            """)
    List<Map<String, Object>> selectSalesPayments(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    /**
     * 商品/服务销售排行（GET /admin/reports/sales-items）：区间内按 (币种, 品类, 明细名) 聚合，
     * 销售额降序，供「哪些商品/服务卖得好」的排行与占比。
     *
     * <p><b>品类归一</b>：{@code ord_order_item.item_type} 既表示品类也表示入口来源——
     * {@code ADD_ON} 是「从加项入口点进来的目录项」，它本身可能是商品也可能是服务。
     * 因此优先取目录项类型 {@code ord_catalog_item.item_type}（PRODUCT/SERVICE），
     * 取不到再退回明细类型，仍取不到才归到 {@code ADD_ON}；{@code ROOM_FEE}（包厢计时费）单独一类。
     *
     * <p><b>口径</b>（与 {@link #selectSalesOrders} 一致）：
     * <ul>
     *   <li>只算**有效明细** {@code i.status = 'ACTIVE'}——待确认加项（{@code PENDING_APPROVAL}）
     *       还没成立、被拒的（{@code REJECTED}）不该计入销售；</li>
     *   <li>只算**有效订单** {@code o.status NOT IN ('CANCELLED','VOIDED')}；</li>
     *   <li>区间按**订单创建时间**闭开区间（与销售报表同一入参口径），不做营业日分档（排行看整段）；</li>
     *   <li>销售额取明细 {@code total_amount}（**已扣折扣**），折扣额单列 {@code discount_amount}
     *       便于核对「原价→实收」；</li>
     *   <li>币种取明细快照 {@code ord_order_item.currency_code}，跨币种分行，**不跨币种求和**
     *       （占比在控制器里按同币种合计计算）。</li>
     * </ul>
     *
     * <p>{@code itemCategory} 为空 = 全部品类；{@code storeId} 为空 = 全部门店（此时按名称跨门店汇总，
     * 并给出 {@code store_count} 售卖门店数）。
     */
    @Select("""
            SELECT i.currency_code AS currency_code,
                   CASE
                     WHEN i.item_type = 'ROOM_FEE' THEN 'ROOM_FEE'
                     WHEN c.item_type IN ('PRODUCT', 'SERVICE', 'PACKAGE') THEN c.item_type
                     WHEN i.item_type IN ('PRODUCT', 'SERVICE', 'PACKAGE') THEN i.item_type
                     ELSE 'ADD_ON'
                   END AS item_category,
                   i.name_snapshot AS item_name,
                   COUNT(DISTINCT o.store_id) AS store_count,
                   SUM(i.quantity) AS quantity,
                   SUM(i.total_amount) AS sales_amount,
                   SUM(i.discount_amount) AS discount_amount,
                   COUNT(DISTINCT i.order_id) AS order_count
            FROM ord_order_item i
            JOIN ord_order o ON o.id = i.order_id AND o.tenant_id = i.tenant_id
            LEFT JOIN ord_catalog_item c ON c.id = i.catalog_item_id
            WHERE i.tenant_id = #{tenantId}
              AND i.status = 'ACTIVE'
              AND o.status NOT IN ('CANCELLED', 'VOIDED')
              AND o.created_at >= #{from} AND o.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
              AND (#{itemCategory} IS NULL OR (CASE
                     WHEN i.item_type = 'ROOM_FEE' THEN 'ROOM_FEE'
                     WHEN c.item_type IN ('PRODUCT', 'SERVICE', 'PACKAGE') THEN c.item_type
                     WHEN i.item_type IN ('PRODUCT', 'SERVICE', 'PACKAGE') THEN i.item_type
                     ELSE 'ADD_ON'
                   END) = #{itemCategory})
            GROUP BY i.currency_code, item_category, i.name_snapshot
            ORDER BY sales_amount DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectSalesItems(@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("itemCategory") String itemCategory, @Param("limit") int limit);

    /**
     * 销售报表**明细**总数：与 {@link #selectSalesDetails} **完全同一套过滤条件**，
     * 否则分页 total 与列表会对不上（前端会以为后面还有页）。
     *
     * <p>默认口径与统计侧一致：{@code status NOT IN ('CANCELLED','VOIDED')}；
     * 传了 {@code status} 就只看该状态（用于复核作废/取消单）。
     */
    @Select("""
            SELECT COUNT(*)
            FROM ord_order o
            WHERE o.tenant_id = #{tenantId}
              AND o.created_at >= #{from} AND o.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
              AND (#{status} IS NULL OR o.status = #{status})
              AND (#{status} IS NOT NULL OR o.status NOT IN ('CANCELLED','VOIDED'))
            """)
    long countSalesDetails(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("status") String status);

    /**
     * 销售报表**明细**：区间内的销售单据（订单），按创建时间倒序分页。
     *
     * <p>包厢名取该订单第一条 KTV 会话的快照 {@code ord_ktv_session.room_name_snapshot}
     * （用相关子查询而不是 JOIN：一张订单换过包厢会有多条会话，JOIN 会把明细行放大、
     * 让分页 total 与 records 数量对不上）。
     *
     * <p>{@code created_at} 是存储值（UTC 墙钟）的 ISO 形态、{@code business_date} 是**营业日**
     * （与统计桶同源）：两个都给出来，前端才能既与其它页面时间口径一致、又能核对「这单算在哪一天」。
     */
    @Select("""
            SELECT o.id AS order_id,
                   o.order_no AS order_no,
                   DATE_FORMAT(o.created_at, '%Y-%m-%dT%H:%i:%s') AS created_at,
                   DATE_FORMAT(DATE_ADD(o.created_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')
                       AS business_date,
                   o.store_id AS store_id,
                   s.name AS store_name,
                   (SELECT k.room_name_snapshot FROM ord_ktv_session k
                     WHERE k.order_id = o.id AND k.tenant_id = o.tenant_id
                     ORDER BY k.id LIMIT 1) AS room_name,
                   o.status AS status,
                   o.currency_code AS currency_code,
                   o.subtotal_amount AS subtotal_amount,
                   o.discount_amount AS discount_amount,
                   o.total_amount AS total_amount,
                   o.paid_amount AS paid_amount,
                   o.refundable_amount AS refundable_amount
            FROM ord_order o
            LEFT JOIN tnt_store s ON s.id = o.store_id AND s.tenant_id = o.tenant_id
            WHERE o.tenant_id = #{tenantId}
              AND o.created_at >= #{from} AND o.created_at < #{to}
              AND (#{storeId} IS NULL OR o.store_id = #{storeId})
              AND (#{status} IS NULL OR o.status = #{status})
              AND (#{status} IS NOT NULL OR o.status NOT IN ('CANCELLED','VOIDED'))
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> selectSalesDetails(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("status") String status,
            @Param("limit") int limit, @Param("offset") long offset,
            @Param("businessDayShiftSeconds") int businessDayShiftSeconds);

    /**
     * 明细页的**支付构成**：按 order_id 取当前页这些单据的成功流水金额（分支付方式）。
     *
     * <p>只查当前页的 order id（{@code IN} 列表），不按区间全量取——否则分页就白做了。
     * 调用方必须保证 {@code orderIds} 非空（空集合会生成非法 SQL）。
     */
    @Select("""
            <script>
            SELECT pi.order_id AS order_id,
                   COALESCE(t.provider, pi.provider) AS provider,
                   t.currency_code AS currency_code,
                   COUNT(*) AS payment_count,
                   SUM(t.amount) AS payment_amount
            FROM pay_transaction t
            JOIN pay_intent pi ON pi.id = t.payment_intent_id
            WHERE t.tenant_id = #{tenantId}
              AND t.status = 'SUCCEEDED'
              AND pi.order_id IN
              <foreach collection="orderIds" item="orderId" open="(" separator="," close=")">#{orderId}</foreach>
            GROUP BY pi.order_id, COALESCE(t.provider, pi.provider), t.currency_code
            </script>
            """)
    List<Map<String, Object>> selectOrderPaymentComposition(@Param("tenantId") Long tenantId,
            @Param("orderIds") List<Long> orderIds);
}
