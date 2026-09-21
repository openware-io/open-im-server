package com.gvchat.platform.resource.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.platform.resource.infra.persistence.mapper.EventOutboxMapper;
import com.gvchat.platform.resource.infra.persistence.mapper.OccupationMapper;
import com.gvchat.platform.resource.infra.persistence.po.OccupationPo;
import com.gvchat.platform.resource.infra.persistence.po.ResEventOutboxPo;
import com.gvchat.protocol.mq.event.ResourceEventTypes;
import com.gvchat.protocol.mq.event.ResourceOccupationReleasedEvent;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 资源占用：并发抢占唯一 + HELD 超时自动释放（乐观锁 version CAS）+ 释放/取消落 Outbox。 */
class OccupationApplicationServiceTest {

  private OccupationMapper occupationMapper;
  private EventOutboxMapper outboxMapper;
  private OccupationApplicationService service;

  @BeforeEach
  void setUp() {
    occupationMapper = mock(OccupationMapper.class);
    outboxMapper = mock(EventOutboxMapper.class);
    service = new OccupationApplicationService(occupationMapper, outboxMapper);
  }

  @Test
  void holdResource_concurrentHundredThreadsOnlyOneWins() throws Exception {
    int threads = 100;
    LocalDateTime startAt = LocalDateTime.of(2025, 1, 1, 10, 0);
    LocalDateTime endAt = LocalDateTime.of(2025, 1, 1, 12, 0);
    // 模拟 DB 行锁：selectValidForUpdate 与 insert 共用同一把锁的占用仓库，insert 时再判重作为唯一约束兜底。
    List<OccupationPo> store = Collections.synchronizedList(new ArrayList<>());
    when(occupationMapper.selectValidForUpdate(any(), any())).thenAnswer(inv -> {
      synchronized (store) {
        return new ArrayList<>(store);
      }
    });
    when(occupationMapper.insert(any(OccupationPo.class))).thenAnswer(inv -> {
      OccupationPo po = inv.getArgument(0);
      synchronized (store) {
        for (OccupationPo o : store) {
          boolean overlap = o.getStartAt().isBefore(po.getEndAt()) && po.getStartAt().isBefore(o.getEndAt());
          if (overlap) {
            throw new IllegalStateException("RESOURCE_OCCUPIED");
          }
        }
        store.add(po);
        return 1;
      }
    });

    CyclicBarrier barrier = new CyclicBarrier(threads);
    CountDownLatch done = new CountDownLatch(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger occupied = new AtomicInteger();
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
            service.holdResource(1L, 100L, 9L, startAt, endAt, "ORDER", 10L, endAt.plusMinutes(30));
            success.incrementAndGet();
          } catch (IllegalStateException e) {
            if ("RESOURCE_OCCUPIED".equals(e.getMessage())) {
              occupied.incrementAndGet();
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
      assertTrue(done.await(30, TimeUnit.SECONDS), "并发占用应在 30s 内完成");
    } finally {
      pool.shutdownNow();
    }
    assertEquals(0, unexpected.size(), "不应有意外异常: " + unexpected);
    assertEquals(1, success.get(), "同一资源同一时段只允许一个占用成功");
    assertEquals(threads - 1, occupied.get(), "其余请求应命中 RESOURCE_OCCUPIED");
  }

  @Test
  void releaseExpiredHolds_releasesExpiredHeldAndWritesOutbox() {
    OccupationPo expired = held(7L, 0, LocalDateTime.now().minusMinutes(16));
    when(occupationMapper.selectExpiredHolds(any(LocalDateTime.class), anyInt())).thenReturn(List.of(expired));
    when(occupationMapper.releaseWithVersion(eq(7L), eq(0), any(LocalDateTime.class))).thenReturn(1);

    int released = service.releaseExpiredHolds(LocalDateTime.now());

    assertEquals(1, released);
    verify(occupationMapper).releaseWithVersion(eq(7L), eq(0), any(LocalDateTime.class));
    ArgumentCaptor<ResEventOutboxPo> captor = ArgumentCaptor.forClass(ResEventOutboxPo.class);
    verify(outboxMapper).insert(captor.capture());
    ResEventOutboxPo outbox = captor.getValue();
    assertEquals(ResourceEventTypes.RESOURCE_OCCUPATION_RELEASED, outbox.getEventType());
    assertEquals("occupation", outbox.getAggregateType());
    assertEquals("7", outbox.getAggregateId());
    assertEquals("PENDING", outbox.getStatus());
    assertNotNull(outbox.getEventId());
    assertNotNull(outbox.getPayloadJson());
  }

  @Test
  void releaseExpiredHolds_skipsRowAlreadyWonByAnotherNode() {
    OccupationPo expired = held(8L, 0, LocalDateTime.now().minusMinutes(16));
    when(occupationMapper.selectExpiredHolds(any(LocalDateTime.class), anyInt())).thenReturn(List.of(expired));
    // 另一节点已释放：version CAS 受影响 0 行
    when(occupationMapper.releaseWithVersion(eq(8L), eq(0), any(LocalDateTime.class))).thenReturn(0);

    int released = service.releaseExpiredHolds(LocalDateTime.now());

    assertEquals(0, released);
    verify(outboxMapper, never()).insert(any(ResEventOutboxPo.class));
  }

  /**
   * 业务时段已结束（end_at 已过）的占用兜底释放：结台/取消时释放失败（资源服务抖动）会留下
   * 「订单已结束、房态仍使用中」，包厢既不能清洁完成也不能再次开台——由本兜底自愈。
   */
  @Test
  void releaseEndedOccupations_releasesEndedOccupationAndWritesOutbox() {
    OccupationPo ended = held(11L, 2, LocalDateTime.now().minusHours(1));
    when(occupationMapper.selectEndedOccupations(any(LocalDateTime.class), anyInt())).thenReturn(List.of(ended));
    when(occupationMapper.releaseWithVersion(eq(11L), eq(2), any(LocalDateTime.class))).thenReturn(1);

    int released = service.releaseEndedOccupations(LocalDateTime.now());

    assertEquals(1, released);
    ArgumentCaptor<ResEventOutboxPo> captor = ArgumentCaptor.forClass(ResEventOutboxPo.class);
    verify(outboxMapper).insert(captor.capture());
    assertEquals(ResourceEventTypes.RESOURCE_OCCUPATION_RELEASED, captor.getValue().getEventType());
    assertEquals("11", captor.getValue().getAggregateId());
  }

  /** 正常营业中的开台占用（end_at 未到）不会被兜底释放。 */
  @Test
  void releaseEndedOccupations_leavesLiveOccupationUntouched() {
    when(occupationMapper.selectEndedOccupations(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

    assertEquals(0, service.releaseEndedOccupations(LocalDateTime.now()));

    verify(occupationMapper, never()).releaseWithVersion(any(), any(), any(LocalDateTime.class));
    verify(outboxMapper, never()).insert(any(ResEventOutboxPo.class));
  }

  @Test
  void releaseOccupation_versionConflictThrows() {
    OccupationPo po = held(9L, 3, LocalDateTime.now().plusMinutes(10));
    when(occupationMapper.selectById(9L)).thenReturn(po);
    when(occupationMapper.releaseWithVersion(eq(9L), eq(3), any(LocalDateTime.class))).thenReturn(0);

    assertThrows(IllegalStateException.class, () -> service.releaseOccupation(9L));
    verify(outboxMapper, never()).insert(any(ResEventOutboxPo.class));
  }

  @Test
  void cancelOccupation_writesCancelledOutbox() {
    OccupationPo po = held(10L, 1, LocalDateTime.now().plusMinutes(10));
    when(occupationMapper.selectById(10L)).thenReturn(po);
    when(occupationMapper.cancelWithVersion(eq(10L), eq(1), any(LocalDateTime.class))).thenReturn(1);

    OccupationPo result = service.cancelOccupation(10L);

    assertEquals("CANCELLED", result.getStatus());
    ArgumentCaptor<ResEventOutboxPo> captor = ArgumentCaptor.forClass(ResEventOutboxPo.class);
    verify(outboxMapper).insert(captor.capture());
    assertEquals(ResourceEventTypes.RESOURCE_OCCUPATION_CANCELLED, captor.getValue().getEventType());
  }

  /**
   * 结台释放：窗口收缩到释放时刻——内存态、落库行（SQL 的 LEAST，见 OccupationMapperReleaseWindowTest）
   * 与 Outbox 事件三者一致。修复前 end_at 停在开台的 24 小时窗口，
   * 按「释放窗口」统计实际使用时长的读方会把一次开台算成 24 小时。
   */
  @Test
  void releaseOccupation_shrinksEndAtToReleaseMoment() throws Exception {
    // 开台写的是 openedAt + 24h（订单侧冲突判定窗口），必须相对当前时钟构造才会落在释放时刻之后
    LocalDateTime startAt = LocalDateTime.now();
    LocalDateTime reservedEndAt = startAt.plusHours(24);
    OccupationPo po = occupation(20L, 0, startAt, reservedEndAt);
    when(occupationMapper.selectById(20L)).thenReturn(po);
    when(occupationMapper.releaseWithVersion(eq(20L), eq(0), any(LocalDateTime.class))).thenReturn(1);

    OccupationPo result = service.releaseOccupation(20L);

    ArgumentCaptor<LocalDateTime> releasedAt = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(occupationMapper).releaseWithVersion(eq(20L), eq(0), releasedAt.capture());
    assertEquals(releasedAt.getValue(), result.getEndAt(), "返回的占用窗口收缩到释放时刻");
    assertTrue(result.getEndAt().isBefore(reservedEndAt), "不再是开台时写的 24 小时窗口");
    ArgumentCaptor<ResEventOutboxPo> captor = ArgumentCaptor.forClass(ResEventOutboxPo.class);
    verify(outboxMapper).insert(captor.capture());
    ResourceOccupationReleasedEvent event = new ObjectMapper().findAndRegisterModules()
        .readValue(captor.getValue().getPayloadJson(), ResourceOccupationReleasedEvent.class);
    assertEquals(releasedAt.getValue().toInstant(ZoneOffset.UTC), event.getEndAt(),
        "Outbox 事件里的窗口同样已收缩，读事件的下游不会读到 24 小时");
  }

  /** 释放只允许提前窗口：end_at 已早于释放时刻时保持原值。 */
  @Test
  void releaseOccupation_doesNotExtendAlreadyEarlierEndAt() {
    OccupationPo po = occupation(21L, 0, LocalDateTime.of(2025, 1, 1, 8, 0), LocalDateTime.of(2025, 1, 1, 9, 0));
    when(occupationMapper.selectById(21L)).thenReturn(po);
    when(occupationMapper.releaseWithVersion(eq(21L), eq(0), any(LocalDateTime.class))).thenReturn(1);

    OccupationPo result = service.releaseOccupation(21L);

    assertEquals(LocalDateTime.of(2025, 1, 1, 9, 0), result.getEndAt(), "已更早的 end_at 不被释放时刻拉长");
  }

  /**
   * 过期 HELD 释放：释放时刻用扫描入参（不各自取时钟、不落 SQL NOW()），窗口收缩且不早于 start_at——
   * 超时未确认的预占不能继续占着 24 小时窗口，也不能缩成倒挂区间。
   * 这里业务时段还在未来（{@code start_at = now + 1h}），收缩结果被兜到 {@code start_at}（零长度区间）。
   */
  @Test
  void releaseExpiredHolds_passesScanNowAndClampsToStartAt() {
    LocalDateTime now = LocalDateTime.of(2026, 3, 1, 22, 0);
    LocalDateTime startAt = now.plusHours(1);
    OccupationPo expired = occupation(22L, 0, startAt, now.plusHours(3));
    when(occupationMapper.selectExpiredHolds(eq(now), anyInt())).thenReturn(List.of(expired));
    when(occupationMapper.releaseWithVersion(eq(22L), eq(0), eq(now))).thenReturn(1);

    int released = service.releaseExpiredHolds(now);

    assertEquals(1, released);
    verify(occupationMapper).releaseWithVersion(eq(22L), eq(0), eq(now));
    assertEquals(startAt, expired.getEndAt(), "未开始即释放：兜到 start_at，区间不倒退");
  }

  /** 过期 HELD 释放（业务时段已开始）：窗口收缩到释放时刻。 */
  @Test
  void releaseExpiredHolds_shrinksWindowStartedBeforeRelease() {
    LocalDateTime now = LocalDateTime.of(2026, 3, 1, 22, 0);
    OccupationPo expired = occupation(24L, 0, now.minusHours(1), now.plusHours(3));
    when(occupationMapper.selectExpiredHolds(eq(now), anyInt())).thenReturn(List.of(expired));
    when(occupationMapper.releaseWithVersion(eq(24L), eq(0), eq(now))).thenReturn(1);

    assertEquals(1, service.releaseExpiredHolds(now));

    assertEquals(now, expired.getEndAt(), "释放时刻即收缩后的窗口终点");
  }

  /** 兜底释放（end_at 已过）：释放时刻同样用扫描入参，且本就更早的 end_at 保持原值。 */
  @Test
  void releaseEndedOccupations_passesScanNowAndKeepsEarlierEndAt() {
    LocalDateTime now = LocalDateTime.of(2026, 3, 1, 22, 0);
    LocalDateTime earlierEndAt = now.minusHours(2);
    OccupationPo ended = occupation(23L, 4, earlierEndAt.minusHours(2), earlierEndAt);
    when(occupationMapper.selectEndedOccupations(eq(now), anyInt())).thenReturn(List.of(ended));
    when(occupationMapper.releaseWithVersion(eq(23L), eq(4), eq(now))).thenReturn(1);

    int released = service.releaseEndedOccupations(now);

    assertEquals(1, released);
    verify(occupationMapper).releaseWithVersion(eq(23L), eq(4), eq(now));
    assertEquals(earlierEndAt, ended.getEndAt(), "已更早的 end_at 保持原值");
  }

  private OccupationPo occupation(long id, int version, LocalDateTime startAt, LocalDateTime endAt) {
    OccupationPo po = new OccupationPo();
    po.setId(id);
    po.setTenantId(1L);
    po.setStoreId(100L);
    po.setResourceId(9L);
    po.setSourceType("ORDER");
    po.setSourceId(10L);
    po.setStartAt(startAt);
    po.setEndAt(endAt);
    po.setStatus("HELD");
    po.setHoldExpiresAt(endAt.plusMinutes(30));
    po.setVersion(version);
    po.setCreatedAt(LocalDateTime.now());
    po.setUpdatedAt(LocalDateTime.now());
    return po;
  }

  private OccupationPo held(long id, int version, LocalDateTime holdExpiresAt) {
    OccupationPo po = new OccupationPo();
    po.setId(id);
    po.setTenantId(1L);
    po.setStoreId(100L);
    po.setResourceId(9L);
    po.setSourceType("ORDER");
    po.setSourceId(10L);
    po.setStartAt(LocalDateTime.of(2025, 1, 1, 10, 0));
    po.setEndAt(LocalDateTime.of(2025, 1, 1, 12, 0));
    po.setStatus("HELD");
    po.setHoldExpiresAt(holdExpiresAt);
    po.setVersion(version);
    po.setCreatedAt(LocalDateTime.now());
    po.setUpdatedAt(LocalDateTime.now());
    return po;
  }
}
