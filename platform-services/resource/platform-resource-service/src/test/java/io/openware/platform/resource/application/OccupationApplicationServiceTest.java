package io.openware.platform.resource.application;

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
import io.openware.platform.resource.domain.occupation.model.Occupation;
import io.openware.platform.resource.domain.occupation.repository.OccupationEventOutbox;
import io.openware.platform.resource.domain.occupation.repository.OccupationRepository;
import io.openware.protocol.mq.event.ResourceEventTypes;
import io.openware.protocol.mq.event.ResourceOccupationReleasedEvent;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 资源占用：并发抢占唯一 + HELD 超时自动释放（乐观锁 version CAS）+ 释放/取消落 Outbox。
 *
 * <p>替身是**领域端口**。窗口收缩与 CAS 的权威实现是 mapper 的单条 SQL（见
 * {@code OccupationMapperReleaseWindowTest}）；这里锁定应用层的编排：传入的 CAS 版本与释放时刻是否正确、
 * 成功/失败时是否追加了出站事件，以及返回给调用方与事件载荷的窗口是否与之一致。
 */
class OccupationApplicationServiceTest {

  private OccupationRepository occupationRepository;
  private OccupationEventOutbox outbox;
  private OccupationApplicationService service;

  @BeforeEach
  void setUp() {
    occupationRepository = mock(OccupationRepository.class);
    outbox = mock(OccupationEventOutbox.class);
    service = new OccupationApplicationService(occupationRepository, outbox);
  }

  @Test
  void holdResource_concurrentHundredThreadsOnlyOneWins() throws Exception {
    int threads = 100;
    LocalDateTime startAt = LocalDateTime.of(2025, 1, 1, 10, 0);
    LocalDateTime endAt = LocalDateTime.of(2025, 1, 1, 12, 0);
    // 模拟 DB 行锁：findValidForUpdate 与 insert 共用同一把锁的占用仓库，insert 时再判重作为唯一约束兜底。
    List<Occupation> store = Collections.synchronizedList(new ArrayList<>());
    when(occupationRepository.findValidForUpdate(any(), any())).thenAnswer(inv -> {
      synchronized (store) {
        return new ArrayList<>(store);
      }
    });
    when(occupationRepository.insert(any(Occupation.class))).thenAnswer(inv -> {
      Occupation po = inv.getArgument(0);
      synchronized (store) {
        for (Occupation o : store) {
          if (o.overlaps(po.startAt(), po.endAt())) {
            throw new IllegalStateException("RESOURCE_OCCUPIED");
          }
        }
        store.add(po);
        return po;
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
    Occupation expired = held(7L, 0, LocalDateTime.now().minusMinutes(16));
    when(occupationRepository.findExpiredHolds(any(LocalDateTime.class), anyInt())).thenReturn(List.of(expired));
    when(occupationRepository.releaseWithVersion(eq(7L), eq(0), any(LocalDateTime.class))).thenReturn(true);

    int released = service.releaseExpiredHolds(LocalDateTime.now());

    assertEquals(1, released);
    verify(occupationRepository).releaseWithVersion(eq(7L), eq(0), any(LocalDateTime.class));
    // 出站记录由适配器落库（PENDING 状态与审计字段），服务只表达"追加一条待投递事件"。
    ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> aggregateId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(outbox).append(eventId.capture(), eventType.capture(), aggregateId.capture(), payload.capture());
    assertEquals(ResourceEventTypes.RESOURCE_OCCUPATION_RELEASED, eventType.getValue());
    assertEquals("7", aggregateId.getValue());
    assertNotNull(eventId.getValue());
    assertNotNull(payload.getValue());
    assertTrue(payload.getValue().contains("\"occupationId\":7"), payload.getValue());
  }

  @Test
  void releaseExpiredHolds_skipsRowAlreadyWonByAnotherNode() {
    Occupation expired = held(8L, 0, LocalDateTime.now().minusMinutes(16));
    when(occupationRepository.findExpiredHolds(any(LocalDateTime.class), anyInt())).thenReturn(List.of(expired));
    // 另一节点已释放：version CAS 未命中
    when(occupationRepository.releaseWithVersion(eq(8L), eq(0), any(LocalDateTime.class))).thenReturn(false);

    int released = service.releaseExpiredHolds(LocalDateTime.now());

    assertEquals(0, released);
    verify(outbox, never()).append(any(), any(), any(), any());
  }

  /**
   * 业务时段已结束（end_at 已过）的占用兜底释放：结台/取消时释放失败（资源服务抖动）会留下
   * 「订单已结束、房态仍使用中」，包厢既不能清洁完成也不能再次开台——由本兜底自愈。
   */
  @Test
  void releaseEndedOccupations_releasesEndedOccupationAndWritesOutbox() {
    Occupation ended = held(11L, 2, LocalDateTime.now().minusHours(1));
    when(occupationRepository.findEndedOccupations(any(LocalDateTime.class), anyInt())).thenReturn(List.of(ended));
    when(occupationRepository.releaseWithVersion(eq(11L), eq(2), any(LocalDateTime.class))).thenReturn(true);

    int released = service.releaseEndedOccupations(LocalDateTime.now());

    assertEquals(1, released);
    ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> aggregateId = ArgumentCaptor.forClass(String.class);
    verify(outbox).append(any(), eventType.capture(), aggregateId.capture(), any());
    assertEquals(ResourceEventTypes.RESOURCE_OCCUPATION_RELEASED, eventType.getValue());
    assertEquals("11", aggregateId.getValue());
  }

  /** 正常营业中的开台占用（end_at 未到）不会被兜底释放。 */
  @Test
  void releaseEndedOccupations_leavesLiveOccupationUntouched() {
    when(occupationRepository.findEndedOccupations(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

    assertEquals(0, service.releaseEndedOccupations(LocalDateTime.now()));

    verify(occupationRepository, never()).releaseWithVersion(any(), anyInt(), any(LocalDateTime.class));
    verify(outbox, never()).append(any(), any(), any(), any());
  }

  @Test
  void releaseOccupation_versionConflictThrows() {
    Occupation po = held(9L, 3, LocalDateTime.now().plusMinutes(10));
    when(occupationRepository.findById(9L)).thenReturn(Optional.of(po));
    when(occupationRepository.releaseWithVersion(eq(9L), eq(3), any(LocalDateTime.class))).thenReturn(false);

    assertThrows(IllegalStateException.class, () -> service.releaseOccupation(9L));
    verify(outbox, never()).append(any(), any(), any(), any());
  }

  @Test
  void cancelOccupation_writesCancelledOutbox() {
    Occupation po = held(10L, 1, LocalDateTime.now().plusMinutes(10));
    when(occupationRepository.findById(10L)).thenReturn(Optional.of(po));
    when(occupationRepository.cancelWithVersion(eq(10L), eq(1), any(LocalDateTime.class))).thenReturn(true);

    Occupation result = service.cancelOccupation(10L);

    assertEquals(Occupation.STATUS_CANCELLED, result.status());
    ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
    verify(outbox).append(any(), eventType.capture(), any(), any());
    assertEquals(ResourceEventTypes.RESOURCE_OCCUPATION_CANCELLED, eventType.getValue());
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
    Occupation po = occupation(20L, 0, startAt, reservedEndAt);
    when(occupationRepository.findById(20L)).thenReturn(Optional.of(po));
    when(occupationRepository.releaseWithVersion(eq(20L), eq(0), any(LocalDateTime.class))).thenReturn(true);

    Occupation result = service.releaseOccupation(20L);

    ArgumentCaptor<LocalDateTime> releasedAt = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(occupationRepository).releaseWithVersion(eq(20L), eq(0), releasedAt.capture());
    assertEquals(releasedAt.getValue(), result.endAt(), "返回的占用窗口收缩到释放时刻");
    assertTrue(result.endAt().isBefore(reservedEndAt), "不再是开台时写的 24 小时窗口");
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(outbox).append(any(), any(), any(), payload.capture());
    ResourceOccupationReleasedEvent event = new ObjectMapper().findAndRegisterModules()
        .readValue(payload.getValue(), ResourceOccupationReleasedEvent.class);
    assertEquals(releasedAt.getValue().toInstant(ZoneOffset.UTC), event.getEndAt(),
        "Outbox 事件里的窗口同样已收缩，读事件的下游不会读到 24 小时");
  }

  /** 释放只允许提前窗口：end_at 已早于释放时刻时保持原值。 */
  @Test
  void releaseOccupation_doesNotExtendAlreadyEarlierEndAt() {
    Occupation po = occupation(21L, 0, LocalDateTime.of(2025, 1, 1, 8, 0), LocalDateTime.of(2025, 1, 1, 9, 0));
    when(occupationRepository.findById(21L)).thenReturn(Optional.of(po));
    when(occupationRepository.releaseWithVersion(eq(21L), eq(0), any(LocalDateTime.class))).thenReturn(true);

    Occupation result = service.releaseOccupation(21L);

    assertEquals(LocalDateTime.of(2025, 1, 1, 9, 0), result.endAt(), "已更早的 end_at 不被释放时刻拉长");
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
    Occupation expired = occupation(22L, 0, startAt, now.plusHours(3));
    when(occupationRepository.findExpiredHolds(eq(now), anyInt())).thenReturn(List.of(expired));
    when(occupationRepository.releaseWithVersion(eq(22L), eq(0), eq(now))).thenReturn(true);

    int released = service.releaseExpiredHolds(now);

    assertEquals(1, released);
    verify(occupationRepository).releaseWithVersion(eq(22L), eq(0), eq(now));
    // 载荷里的窗口被兜到 start_at，区间不倒退（落库行的同一规则由 SQL 的 GREATEST 保证）
    assertTrue(releasedEvent().getEndAt().equals(startAt.toInstant(ZoneOffset.UTC)),
        "未开始即释放：窗口兜到 start_at，区间不倒退");
  }

  /** 过期 HELD 释放（业务时段已开始）：窗口收缩到释放时刻。 */
  @Test
  void releaseExpiredHolds_shrinksWindowStartedBeforeRelease() {
    LocalDateTime now = LocalDateTime.of(2026, 3, 1, 22, 0);
    Occupation expired = occupation(24L, 0, now.minusHours(1), now.plusHours(3));
    when(occupationRepository.findExpiredHolds(eq(now), anyInt())).thenReturn(List.of(expired));
    when(occupationRepository.releaseWithVersion(eq(24L), eq(0), eq(now))).thenReturn(true);

    int released = service.releaseExpiredHolds(now);

    assertEquals(1, released);
    assertTrue(releasedEvent().getEndAt().equals(now.toInstant(ZoneOffset.UTC)),
        "释放时刻即收缩后的窗口终点");
  }

  /** 兜底释放（end_at 已过）：释放时刻同样用扫描入参，且本就更早的 end_at 保持原值。 */
  @Test
  void releaseEndedOccupations_passesScanNowAndKeepsEarlierEndAt() {
    LocalDateTime now = LocalDateTime.of(2026, 3, 1, 22, 0);
    LocalDateTime earlierEndAt = now.minusHours(2);
    Occupation ended = occupation(23L, 4, earlierEndAt.minusHours(2), earlierEndAt);
    when(occupationRepository.findEndedOccupations(eq(now), anyInt())).thenReturn(List.of(ended));
    when(occupationRepository.releaseWithVersion(eq(23L), eq(4), eq(now))).thenReturn(true);

    int released = service.releaseEndedOccupations(now);

    assertEquals(1, released);
    verify(occupationRepository).releaseWithVersion(eq(23L), eq(4), eq(now));
    assertTrue(releasedEvent().getEndAt().equals(earlierEndAt.toInstant(ZoneOffset.UTC)),
        "已更早的 end_at 保持原值");
  }

  /** 从出站端口捕获的载荷反序列化出释放事件（Instant 字段按纪元秒序列化，用对象断言比字符串匹配稳健）。 */
  private ResourceOccupationReleasedEvent releasedEvent() {
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(outbox).append(any(), any(), any(), payload.capture());
    try {
      return new ObjectMapper().findAndRegisterModules()
          .readValue(payload.getValue(), ResourceOccupationReleasedEvent.class);
    } catch (Exception e) {
      throw new AssertionError("出站载荷无法反序列化为释放事件: " + payload.getValue(), e);
    }
  }

  private static Occupation occupation(long id, int version, LocalDateTime startAt, LocalDateTime endAt) {
    return new Occupation(id, 1L, 100L, 9L, "ORDER", 10L, startAt, endAt,
        Occupation.STATUS_HELD, endAt.plusMinutes(30), version);
  }

  private static Occupation held(long id, int version, LocalDateTime holdExpiresAt) {
    return new Occupation(id, 1L, 100L, 9L, "ORDER", 10L,
        LocalDateTime.of(2025, 1, 1, 10, 0), LocalDateTime.of(2025, 1, 1, 12, 0),
        Occupation.STATUS_HELD, holdExpiresAt, version);
  }
}
