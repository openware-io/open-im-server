package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.time.StoreTimeService;
import io.openware.platform.order.infra.persistence.mapper.DailySerialMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单据号生成器（订单号 / 预约号）——**全仓唯一的单号格式实现**。
 *
 * <h2>单号规则（定稿）</h2>
 * <pre>
 *   &lt;前缀&gt;&lt;yyyyMMdd&gt;&lt;当日序号&gt;
 *   订单号   O202609190001     前缀 O（Order）
 *   预约号   R202609190001     前缀 R（Reservation）
 * </pre>
 * <ul>
 *   <li><b>前缀</b>：订单 {@code O}、预约 {@code R}，沿用既有前缀习惯（人眼一眼分辨单据类型，
 *       历史单号不动）；同一前缀内的序号各自独立计数。</li>
 *   <li><b>日期取门店营业日</b>：复用仓库唯一实现 {@link StoreTimeService#businessDate}，
 *       口径为平台默认 <b>Asia/Shanghai + 04:00 营业日切点</b>（凌晨 04:00 前的单据归**前一营业日**）。
 *       不用 {@code new Date()} / UTC 自然日 —— 通宵场次必须落在开单那天。
 *       为什么用平台默认口径而不是逐门店读 {@code tnt_store.timezone}：order 域没有门店时区客户端
 *       （只有营业时间客户端），为发号新增一次远程调用会把「租户服务抖动」变成「下不了单」；
 *       这与 {@code ReportTimeBuckets} 的报表营业日口径一致，见
 *       {@code docs/renovation/MULTI_TIMEZONE_DESIGN.md} §4.2 S19。</li>
 *   <li><b>当日序号</b>：按 <b>租户</b>（不含门店）每日从 1 递增，最小定宽 4 位（{@code 0001}）。
 *       <b>不按门店分桶的理由</b>：{@code ord_order} 的唯一键是 {@code uk_ord_order_tenant_no
 *       (tenant_id, order_no)}，{@code ord_reservation} 是 {@code uk_ord_reservation_tenant_no}；
 *       若各门店各自从 0001 开始，同租户两个门店同日必然生成同一个单号并撞唯一键。
 *       要让门店可区分，只能把门店编码写进单号（如 {@code O-K01-20260919-0001}），
 *       而定稿格式不含门店位，因此序号按租户维度分配。</li>
 *   <li><b>超限处理</b>：当日超过 9999 单**自然加宽**（{@code O2026091910000}，5 位起），
 *       不回绕、不截断、不复用 —— 单号列是 {@code varchar(64)}，加宽不影响存储与唯一键。</li>
 * </ul>
 *
 * <h2>并发与幂等</h2>
 * <ul>
 *   <li><b>并发安全</b>：序号在数据库里分配（{@code ord_daily_serial} 行锁 + 唯一键
 *       {@code (tenant_id, biz_type, business_date)}），不依赖 JVM 内的计数器，
 *       多实例/多线程并发下单不会拿到同一个号。历史上 {@code "O" + System.currentTimeMillis()}
 *       在同一毫秒并发时会重复，这里从根上消除。</li>
 *   <li><b>幂等重试不换号</b>：单号只在**单据 INSERT 之前**生成一次，任何更新路径都不重写单号；
 *       预约还有 Idempotency-Key 语义（{@code ReservationApplicationService.create} 先查
 *       {@code (tenant_id, idempotency_key)}，命中即返回既有预约），重试返回原号，不会换号。</li>
 * </ul>
 *
 * <h2>失败语义（失败关闭，绝不降级）</h2>
 * <p>序列表不可用（连接失败、超时、语句报错、分配重试耗尽）时抛 {@code 503}
 * {@value #CODE_SEQUENCE_UNAVAILABLE}，**整个创建操作失败**。不降级到时间戳/UUID：
 * 降级会静默产生「不符合规则、可能重复」的单号，而单号是写进唯一键、印在小票上、
 * 被人工与对账引用的标识，宁可让调用方看到「稍后重试」。
 *
 * <h2>事务与空号（gap）</h2>
 * <p>发号走 {@code REQUIRED}（默认传播）：调用方有事务就加入，没有就新开一个。
 * <b>为什么不用 {@code REQUIRES_NEW}</b>：嵌套事务会额外占用一条连接（外层事务已持有一条），
 * 本仓没有任何 Hikari 池大小配置（默认 10），当并发创建把连接池占到接近满时，
 * 「每个创建持 1 条再等第 2 条」会互相等 30s 连接超时（连接池自锁），代价远大于收益。
 * 加入调用方事务后，序号行的排他锁持有到调用方事务提交：对同一「租户 + 单据类型 + 营业日」
 * 的并发发号天然串行（这正是我们要的），且调用方回滚时序号一并回滚。
 * 锁范围可控：预约创建的所有校验（含资源域远程调用）都在发号**之前**完成，
 * 发号之后只剩一次 INSERT；开台路径发号之后还有会话创建，因此同租户并发开台会排队，
 * 但不会重号、也不会失败（同租户并发开台量本就极低）。
 * 若调用方不是事务方法（{@code OrderController.createOrder}），发号自己就是一个短事务。
 *
 * <p><b>空洞（gap）</b>：只保证递增与唯一，不保证连续，**永不回绕复用**。两类残留空号：
 * ① 并发同 Idempotency-Key 重试里抢号失败的那一路——它捕获唯一键冲突并返回既有预约，事务提交，
 * 已分配的号随之作废（当日序号 0001、0003）；② 「快速开台」不是事务方法，发号先行提交，
 * 随后的订单 INSERT 失败也会作废一个号。
 */
@Slf4j
@Component
public class DailySerialNumberGenerator {

    /** 单据类型：决定单号前缀，也决定序列表里 {@code biz_type} 的取值。 */
    public enum DocType {
        /** 统一订单：{@code ord_order.order_no}，前缀 O。 */
        ORDER("O"),
        /** 预约：{@code ord_reservation.reservation_no}，前缀 R。 */
        RESERVATION("R");

        private final String prefix;

        DocType(String prefix) {
            this.prefix = prefix;
        }

        /** 单号前缀（O / R）。 */
        public String prefix() {
            return prefix;
        }
    }

    /** 失败关闭错误码：序号服务不可用（HTTP 503，前端按「稍后重试」处理）。 */
    public static final String CODE_SEQUENCE_UNAVAILABLE = "DOC_NO_SEQUENCE_UNAVAILABLE";

    /** 序号的**最小**定宽：不足左补 0（0001）；超过 9999 自然加宽，不回绕。 */
    public static final int MIN_SEQ_WIDTH = 4;

    /** 日期段格式：{@code yyyyMMdd}（ISO 基本格式，与 {@code 20260919} 一致）。 */
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.BASIC_ISO_DATE;

    /** 分配并发冲突（建行被抢）时的重试上限：正常竞争一次即成功，重试只兜异常路径。 */
    private static final int MAX_ALLOCATE_ATTEMPTS = 3;

    private final DailySerialMapper dailySerialMapper;

    public DailySerialNumberGenerator(DailySerialMapper dailySerialMapper) {
        this.dailySerialMapper = dailySerialMapper;
    }

    /**
     * 取门店营业日（单号日期段的口径）：平台默认 <b>Asia/Shanghai + 04:00 切点</b>，
     * 直接委托 {@link StoreTimeService#businessDate(Instant, ZoneId, java.time.LocalTime)}，
     * 不在本类重写「减一天」的判定。
     */
    public static LocalDate businessDate(Instant moment) {
        Instant instant = moment == null ? Instant.now() : moment;
        return StoreTimeService.businessDate(instant,
                ZoneId.of(StoreTimeService.DEFAULT_TIMEZONE),
                StoreTimeService.DEFAULT_BUSINESS_DAY_CUTOFF);
    }

    /**
     * 生成下一个单号（当前时刻）。
     *
     * @param docType  单据类型（订单 / 预约），决定前缀与独立序号
     * @param tenantId 租户ID（必填）：序号的唯一域
     * @return 形如 {@code O202609190001} / {@code R202609190001}
     * @throws BusinessException 序号服务不可用（{@value #CODE_SEQUENCE_UNAVAILABLE}，失败关闭）
     */
    // 两个重载都必须标注 @Transactional：外部入口（Controller / ApplicationService）调用时由代理负责
    // 「没有事务就新开一个」，下面同类内部的委托调用不经过代理，但外层入口已经把事务准备好了。
    // 若只标注被委托的那个重载，外部调用本方法时会因为自调用而完全没有事务，
    // 「自增 → 读回」之间就可能读到别的会话的值（并发下会发出重复单号）。
    @Transactional
    public String next(DocType docType, Long tenantId) {
        return next(docType, tenantId, Instant.now());
    }

    /**
     * 生成下一个单号（指定时刻，供单测固定跨日/跨切点边界）。
     *
     * @param moment 生成时刻（绝对时刻；{@code null} 视为当前时刻）
     */
    @Transactional
    public String next(DocType docType, Long tenantId, Instant moment) {
        if (docType == null) {
            throw new ApiException(400, "DOC_TYPE_REQUIRED", "缺少单据类型，无法生成单号");
        }
        if (tenantId == null) {
            throw new ApiException(400, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文，无法生成单号");
        }
        LocalDate businessDate = businessDate(moment);
        long sequence;
        try {
            sequence = allocate(tenantId, docType.name(), businessDate);
        } catch (BusinessException business) {
            throw business;
        } catch (RuntimeException failure) {
            // 失败关闭：记录原始异常，但不产出任何降级单号。
            log.error("单据号分配失败（失败关闭，不降级为时间戳/UUID）: tenantId={}, bizType={}, businessDate={}",
                    tenantId, docType, businessDate, failure);
            throw new BusinessException(CODE_SEQUENCE_UNAVAILABLE,
                    "单号生成失败：序号服务不可用，请稍后重试");
        }
        return format(docType, businessDate, sequence);
    }

    /** 拼装单号：{@code <前缀><yyyyMMdd><当日序号>}（序号不足 4 位左补 0，超出自然加宽）。 */
    public static String format(DocType docType, LocalDate businessDate, long sequence) {
        return docType.prefix() + DATE_PART.format(businessDate)
                + padSequence(sequence);
    }

    /** 序号定宽：最小 {@value #MIN_SEQ_WIDTH} 位；10000 → {@code 10000}（加宽，不回绕）。 */
    private static String padSequence(long sequence) {
        String raw = Long.toString(sequence);
        if (raw.length() >= MIN_SEQ_WIDTH) {
            return raw;
        }
        return "0".repeat(MIN_SEQ_WIDTH - raw.length()) + raw;
    }

    /**
     * 分配当日序号（必须在调用方的事务内执行）。
     *
     * <p>先自增、再建行、被抢则重试：三条语句都在同一个事务里，
     * 自增持锁期间的读回必然是本事务写入的值，因此并发下不会出现两路拿到同一个序号。
     */
    private long allocate(Long tenantId, String bizType, LocalDate businessDate) {
        LocalDateTime now = LocalDateTime.now();
        for (int attempt = 1; attempt <= MAX_ALLOCATE_ATTEMPTS; attempt++) {
            if (dailySerialMapper.incrementIfPresent(tenantId, bizType, businessDate, now) == 1) {
                Long sequence = dailySerialMapper.selectCurrentSeq(tenantId, bizType, businessDate);
                if (sequence == null) {
                    // 自增成功后行必然存在；读不到就是数据被并发删除等异常态，直接失败关闭。
                    throw new BusinessException(CODE_SEQUENCE_UNAVAILABLE,
                            "单号序号读取失败，请稍后重试");
                }
                return sequence;
            }
            try {
                dailySerialMapper.insertFirst(tenantId, bizType, businessDate, now);
                return 1L;
            } catch (DuplicateKeyException concurrentCreate) {
                // 另一个事务刚建了同一行（并发首单）：本轮没抢到，下一轮走自增分支。
                log.debug("当日序号行并发创建，重试自增: tenantId={}, bizType={}, businessDate={}, attempt={}",
                        tenantId, bizType, businessDate, attempt);
            }
        }
        log.warn("当日序号分配重试耗尽: tenantId={}, bizType={}, businessDate={}", tenantId, bizType, businessDate);
        throw new BusinessException(CODE_SEQUENCE_UNAVAILABLE, "单号序号分配冲突，请稍后重试");
    }
}
