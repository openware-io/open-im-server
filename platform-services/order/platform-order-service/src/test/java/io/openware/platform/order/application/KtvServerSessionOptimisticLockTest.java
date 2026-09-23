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
import io.openware.platform.order.domain.ktv.model.KtvServerSessionStatus;
import io.openware.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import io.openware.platform.order.infra.persistence.mapper.KtvServerSessionMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.po.KtvServerSessionPo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 服务人员点单状态机乐观锁：并发 start 时 version 冲突只允许一个成功（100 线程）。 */
class KtvServerSessionOptimisticLockTest {

  private final KtvServerSessionMapper sessionMapper = mock(KtvServerSessionMapper.class);
  private final OrderMapper orderMapper = mock(OrderMapper.class);
  private final KtvPricingPlanProvider pricingPlanProvider = mock(KtvPricingPlanProvider.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
  private final KtvServerSessionApplicationService service =
      new KtvServerSessionApplicationService(sessionMapper, orderMapper, pricingPlanProvider, auditClient, orderItemMapper);

  @Test
  void start_throwsVersionConflictWhenOptimisticUpdateAffectsZeroRows() {
    KtvServerSessionPo po = session(KtvServerSessionStatus.ORDERED);
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.updateWithVersion(any())).thenReturn(0);

    BusinessException ex = assertThrows(BusinessException.class, () -> service.start(po.getId()));

    assertEquals("SESSION_VERSION_CONFLICT", ex.getCode());
  }

  @Test
  void start_concurrentHundredThreadsOnlyOneWins() throws Exception {
    int threads = 100;
    long sessionId = 1L;
    when(sessionMapper.selectById(sessionId)).thenAnswer(inv -> session(KtvServerSessionStatus.ORDERED));
    AtomicInteger updates = new AtomicInteger();
    when(sessionMapper.updateWithVersion(any())).thenAnswer(inv -> updates.compareAndSet(0, 1) ? 1 : 0);

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

  private static KtvServerSessionPo session(KtvServerSessionStatus status) {
    KtvServerSessionPo po = new KtvServerSessionPo();
    po.setId(1L);
    po.setTenantId(1L);
    po.setOrderId(10L);
    po.setStatus(status.name());
    po.setOrderedAt(LocalDateTime.now().minusMinutes(5));
    po.setRoundingDirection(KtvRoundingDirection.CONSUMER_FAVOR.name());
    po.setIncrementMinutes(30);
    po.setPricePerInc(5000L);
    po.setVersion(0);
    return po;
  }
}
