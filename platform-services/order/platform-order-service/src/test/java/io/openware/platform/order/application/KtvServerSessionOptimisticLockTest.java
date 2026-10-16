package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.domain.ktv.model.KtvRoundingDirection;
import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import io.openware.platform.order.domain.ktv.model.KtvServerSessionStatus;
import io.openware.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import io.openware.platform.order.domain.ktv.repository.KtvServerSessionRepository;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 服务人员点单状态机乐观锁：并发 start 时 version 冲突只允许一个成功（100 线程）。
 *
 * <p>这是本子域唯一一条真正验证「受影响 0 行 = 并发冲突」的用例，**故意保留**：迁移到领域端口后，
 * CAS 仍是 {@code KtvServerSessionMapper#updateWithVersion} 那条
 * {@code UPDATE ... WHERE id = ? AND version = ?}，端口只是把它翻译成
 * {@code boolean}（{@code false} = 0 行）。下面的桩按「第一条成功、其余冲突」模拟数据库，
 * 与被测代码传进来的版本号无关——真正判断版本的是 SQL，不是 Java。
 */
class KtvServerSessionOptimisticLockTest {

  private final KtvServerSessionRepository sessionRepository = mock(KtvServerSessionRepository.class);
  private final OrderMapper orderMapper = mock(OrderMapper.class);
  private final KtvPricingPlanProvider pricingPlanProvider = mock(KtvPricingPlanProvider.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
  private final KtvServerSessionApplicationService service =
      new KtvServerSessionApplicationService(sessionRepository, orderMapper, pricingPlanProvider, auditClient,
          orderItemMapper);

  @Test
  void start_throwsVersionConflictWhenOptimisticUpdateAffectsZeroRows() {
    KtvServerSession session = session(KtvServerSessionStatus.ORDERED);
    when(sessionRepository.findById(session.id())).thenReturn(Optional.of(session));
    // 受影响 0 行：端口必须把它翻译成 false（并发冲突），服务转成 SESSION_VERSION_CONFLICT。
    when(sessionRepository.updateWithVersion(any())).thenReturn(false);

    BusinessException ex = assertThrows(BusinessException.class, () -> service.start(session.id()));

    assertEquals("SESSION_VERSION_CONFLICT", ex.getCode());
  }

  @Test
  void start_concurrentHundredThreadsOnlyOneWins() throws Exception {
    int threads = 100;
    long sessionId = 1L;
    when(sessionRepository.findById(sessionId))
        .thenAnswer(inv -> Optional.of(session(KtvServerSessionStatus.ORDERED)));
    AtomicInteger updates = new AtomicInteger();
    when(sessionRepository.updateWithVersion(any()))
        .thenAnswer(inv -> updates.compareAndSet(0, 1));

    CyclicBarrier barrier = new CyclicBarrier(threads);
    CountDownLatch done = new CountDownLatch(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger conflict = new AtomicInteger();
    List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
    try {
      for (int i = 0; i < threads; i++) {
        pool.execute(() -> {
          try {
            barrier.await();
          } catch (InterruptedException | BrokenBarrierException e) {
            unexpected.add(e);
            done.countDown();
            return;
          }
          try {
            service.start(sessionId);
            success.incrementAndGet();
          } catch (BusinessException e) {
            if ("SESSION_VERSION_CONFLICT".equals(e.getCode())) {
              conflict.incrementAndGet();
            } else {
              unexpected.add(e);
            }
          } catch (RuntimeException e) {
            unexpected.add(e);
          } finally {
            done.countDown();
          }
        });
      }
      assertTrue(done.await(30, TimeUnit.SECONDS), "并发 start 应在 30s 内完成");
    } finally {
      pool.shutdownNow();
    }
    assertEquals(0, unexpected.size(), "不应有意外异常: " + unexpected);
    assertEquals(1, success.get(), "同一会话并发 start 只允许一个成功");
    assertEquals(threads - 1, conflict.get(), "其余请求应命中 SESSION_VERSION_CONFLICT");
  }

  /** 点单夹具（领域模型不可变，逐列对应迁移前的持久化对象）。 */
  private static KtvServerSession session(KtvServerSessionStatus status) {
    return new KtvServerSession(1L, 1L, null, null, 10L, null, null, null,
        LocalDateTime.now().minusMinutes(5), null, null, null, 30,
        KtvRoundingDirection.CONSUMER_FAVOR.name(), 5000L, null, null, null, null, null, status.name(), 0);
  }
}
