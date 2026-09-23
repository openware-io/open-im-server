package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.platform.order.application.DailySerialNumberGenerator.DocType;
import io.openware.platform.order.infra.client.PaymentCollectedClient;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 单据号规则集成测试（H2 MODE=MySQL + 真 Flyway + 真 MyBatis，不是 mock）：
 * {@code <前缀><yyyyMMdd><当日序号>}（{@code O202609190001} / {@code R202609190001}）。
 *
 * <p>覆盖验收点：
 * <ol>
 *   <li>同租户同营业日连续递增、可读（前缀 + 日期 + 4 位定宽序号）；</li>
 *   <li>订单与预约各自独立计数；租户之间互不影响；</li>
 *   <li>跨营业日重置为 0001；营业日切点 04:00 前归前一营业日（复用 StoreTimeService 口径）；</li>
 *   <li>并发（多线程）不重号；</li>
 *   <li>序号超过 9999 自然加宽（不回绕）；</li>
 *   <li>无租户上下文也能发号（序号表访问 {@code @InterceptorIgnore(tenantLine="true")} +
 *       显式 tenantId；否则租户拦截器会因「租户上下文缺失」直接抛错）——本类全程不设置
 *       {@code TenantContextHolder}，本身就是这条断言的证明。</li>
 * </ol>
 *
 * <p>每个用例用**独立 tenantId**：H2 库在同一个 JVM 内是共享的
 * （{@code jdbc:h2:mem:order_test;DB_CLOSE_DELAY=-1}），用独立租户才能断言「从 0001 开始」。
 */
@SpringBootTest
@ActiveProfiles("test")
class DailySerialNumberGeneratorTest {

    /** 2026-09-19 20:00（Asia/Shanghai）→ 营业日 2026-09-19。 */
    private static final Instant MOMENT_0919 = Instant.parse("2026-09-19T12:00:00Z");
    /** 2026-09-20 20:00（Asia/Shanghai）→ 营业日 2026-09-20。 */
    private static final Instant MOMENT_0920 = Instant.parse("2026-09-20T12:00:00Z");
    /** 2026-09-19 01:00（Asia/Shanghai，早于 04:00 切点）→ 营业日仍是 2026-09-18。 */
    private static final Instant MOMENT_0919_EARLY = Instant.parse("2026-09-18T17:00:00Z");
    /** 2026-09-19 04:00（Asia/Shanghai，正好等于切点）→ 归当天 2026-09-19（右开）。 */
    private static final Instant MOMENT_0919_CUTOFF = Instant.parse("2026-09-18T20:00:00Z");

    // 每个用例一个独立租户 ID（H2 库跨用例共享）。
    private static final long TENANT_ORDER = 9_900_101L;
    private static final long TENANT_RESERVATION = 9_900_102L;
    private static final long TENANT_RESET = 9_900_103L;
    private static final long TENANT_CUTOFF = 9_900_104L;
    private static final long TENANT_CONCURRENT = 9_900_105L;
    private static final long TENANT_WIDEN = 9_900_106L;
    private static final long TENANT_ISOLATION_A = 9_900_107L;
    private static final long TENANT_ISOLATION_B = 9_900_108L;

    @MockitoBean
    private MqProducer mqProducer;

    @MockitoBean
    private MqConsumerFactory mqConsumerFactory;

    @MockitoBean
    private AuditClient auditClient;

    @MockitoBean
    private EventOutboxRelay eventOutboxRelay;

    @MockitoBean
    private ResourceStateClient resourceStateClient;

    @MockitoBean
    private PaymentCollectedClient paymentCollectedClient;

    @Autowired
    private DailySerialNumberGenerator generator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void orderNumberIsReadableAndIncrementsWithinTheSameBusinessDay() {
        assertEquals("O202609190001", generator.next(DocType.ORDER, TENANT_ORDER, MOMENT_0919));
        assertEquals("O202609190002", generator.next(DocType.ORDER, TENANT_ORDER, MOMENT_0919));
        assertEquals("O202609190003", generator.next(DocType.ORDER, TENANT_ORDER, MOMENT_0919));
    }

    /** 预约号与订单号各自一条序号线（同为当日第 1 单时互不顶号），前缀区分单据类型。 */
    @Test
    void reservationNumberHasItsOwnSequenceAndPrefix() {
        assertEquals("O202609190001", generator.next(DocType.ORDER, TENANT_RESERVATION, MOMENT_0919));
        assertEquals("R202609190001", generator.next(DocType.RESERVATION, TENANT_RESERVATION, MOMENT_0919));
        assertEquals("R202609190002", generator.next(DocType.RESERVATION, TENANT_RESERVATION, MOMENT_0919));
        assertEquals("O202609190002", generator.next(DocType.ORDER, TENANT_RESERVATION, MOMENT_0919));
    }

    /** 跨营业日重置：09-19 发到 0002，09-20 从 0001 重新开始。 */
    @Test
    void sequenceResetsOnTheNextBusinessDay() {
        assertEquals("O202609190001", generator.next(DocType.ORDER, TENANT_RESET, MOMENT_0919));
        assertEquals("O202609190002", generator.next(DocType.ORDER, TENANT_RESET, MOMENT_0919));

        assertEquals("O202609200001", generator.next(DocType.ORDER, TENANT_RESET, MOMENT_0920));
        // 回到 09-19 仍继续 09-19 的序号（营业日粒度，不因调用顺序回退）。
        assertEquals("O202609190003", generator.next(DocType.ORDER, TENANT_RESET, MOMENT_0919));
    }

    /** 营业日口径：04:00 前归前一营业日，恰好 04:00（右开）归当天。 */
    @Test
    void businessDayFollowsStoreTimeServiceCutoff() {
        assertEquals(LocalDate.of(2026, 9, 18), DailySerialNumberGenerator.businessDate(MOMENT_0919_EARLY));
        assertEquals(LocalDate.of(2026, 9, 19), DailySerialNumberGenerator.businessDate(MOMENT_0919_CUTOFF));

        assertEquals("O202609180001", generator.next(DocType.ORDER, TENANT_CUTOFF, MOMENT_0919_EARLY));
        assertEquals("O202609190001", generator.next(DocType.ORDER, TENANT_CUTOFF, MOMENT_0919_CUTOFF));
    }

    /** 租户隔离：不同租户同一天都从 0001 开始（序号域是租户，与唯一键 (tenant_id, order_no) 一致）。 */
    @Test
    void sequencesAreIsolatedPerTenant() {
        assertEquals("O202609190001", generator.next(DocType.ORDER, TENANT_ISOLATION_A, MOMENT_0919));
        assertEquals("O202609190001", generator.next(DocType.ORDER, TENANT_ISOLATION_B, MOMENT_0919));
        assertEquals("O202609190002", generator.next(DocType.ORDER, TENANT_ISOLATION_A, MOMENT_0919));
    }

    /**
     * 并发不重号：16 个线程各发 4 个号，32 个号必须**两两不同**且恰好覆盖 0001–0032。
     *
     * <p>这是原实现的缺陷场景（{@code "O" + System.currentTimeMillis()} 同毫秒并发生重），
     * 也是本次改造的核心验收点。
     */
    @Test
    void concurrentAllocationsNeverDuplicate() throws Exception {
        int threads = 16;
        int perThread = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                return generator.next(DocType.ORDER, TENANT_CONCURRENT, MOMENT_0919);
            });
        }
        try {
            List<Future<String>> first = new ArrayList<>();
            for (Callable<String> task : tasks) {
                first.add(pool.submit(task));
            }
            start.countDown();
            List<String> numbers = new ArrayList<>();
            for (Future<String> future : first) {
                numbers.add(future.get(60, TimeUnit.SECONDS));
            }
            // 每个线程继续连发，制造同一时刻的重叠分配。
            List<Future<List<String>>> rest = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                rest.add(pool.submit(() -> {
                    List<String> produced = new ArrayList<>();
                    for (int n = 1; n < perThread; n++) {
                        produced.add(generator.next(DocType.ORDER, TENANT_CONCURRENT, MOMENT_0919));
                    }
                    return produced;
                }));
            }
            for (Future<List<String>> future : rest) {
                numbers.addAll(future.get(60, TimeUnit.SECONDS));
            }

            Set<String> distinct = new HashSet<>(numbers);
            assertEquals(threads * perThread, numbers.size(), "发出的号数量不对: " + numbers);
            assertEquals(numbers.size(), distinct.size(), "并发出现重复单号: " + numbers);
            Set<String> expected = new HashSet<>();
            for (int seq = 1; seq <= threads * perThread; seq++) {
                expected.add(String.format("O20260919%04d", seq));
            }
            assertEquals(expected, distinct, "并发发号必须恰好覆盖当日 0001–0032，无空洞无重号");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 超过 9999 自然加宽（5 位），不回绕、不复用：O2026091910000。 */
    @Test
    void sequenceWidensBeyondFourDigitsInsteadOfWrapping() {
        assertEquals("O202609190001", generator.next(DocType.ORDER, TENANT_WIDEN, MOMENT_0919));
        jdbcTemplate.update("UPDATE ord_daily_serial SET current_seq = 9999 "
                        + "WHERE tenant_id = ? AND biz_type = ? AND business_date = ?",
                TENANT_WIDEN, DocType.ORDER.name(), java.sql.Date.valueOf(LocalDate.of(2026, 9, 19)));

        String widened = generator.next(DocType.ORDER, TENANT_WIDEN, MOMENT_0919);

        assertEquals("O2026091910000", widened);
        assertEquals(14, widened.length(), "前缀 1 + 日期 8 + 序号 5（超限从 4 位加宽）= 14");
        // 定宽/加宽是纯函数，单独钉死边界。
        assertEquals("O202609190001", DailySerialNumberGenerator.format(DocType.ORDER,
                LocalDate.of(2026, 9, 19), 1));
        assertEquals("R2026091910000", DailySerialNumberGenerator.format(DocType.RESERVATION,
                LocalDate.of(2026, 9, 19), 10000));
        assertEquals("R202609190001", DailySerialNumberGenerator.format(DocType.RESERVATION,
                LocalDate.of(2026, 9, 19), 1));
    }

    /** 生成的号必须是「前缀 + 8 位日期 + 至少 4 位序号」，且日期段与营业日一致。 */
    @Test
    void generatedNumberMatchesTheDocumentedShape() {
        String number = generator.next(DocType.RESERVATION, 9_900_109L, MOMENT_0919);

        assertNotNull(number);
        assertTrue(number.matches("^R\\d{8}\\d{4,}$"), number);
        assertEquals("R202609190001", number);
        assertEquals(LocalDate.of(2026, 9, 19),
                LocalDate.parse(number.substring(1, 9), java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
    }
}
