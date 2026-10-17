package io.openware.im.user.application.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.im.user.api.authorization.PrivateMessageAuthorizationQuery;
import io.openware.im.user.api.authorization.PrivateMessageAuthorizationSnapshot;
import io.openware.im.user.domain.social.model.FriendRelation;
import io.openware.im.user.domain.social.model.FriendStatus;
import io.openware.im.user.domain.social.repository.AuditSnapshot;
import io.openware.im.user.domain.social.repository.FriendRelationRepository;
import io.openware.im.user.domain.social.repository.Persisted;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 锁定 {@code FriendAuthorizationQueryService} 的授权版本语义。
 *
 * <p><b>为什么这组测试先于 V2 改造存在</b>：该服务的版本号取自
 * {@code updatedAt != null ? updatedAt : createdAt}（epoch millis），并经
 * {@code PrivateMessageAuthorizationSnapshot} 跨服务供 im-message-service 判定私信是否可发。
 * 把审计列移出领域模型会触及这两个时刻，因此在改动前用测试把**当前行为**钉住：
 * 关系未变更时版本稳定、变更后版本前移、缺 {@code updatedAt} 时回退到 {@code createdAt}。
 */
class FriendAuthorizationQueryServiceTest {

  private static final long USER_ID = 7L;
  private static final long PEER_ID = 9L;

  private final FriendRelationRepository repository = mock(FriendRelationRepository.class);
  private final FriendAuthorizationQueryService service = new FriendAuthorizationQueryService(repository);

  @Test
  void normalBothDirectionsGrantsAndUsesLaterTimestamp() {
    LocalDateTime mine = LocalDateTime.of(2026, 1, 1, 0, 0);
    LocalDateTime peers = LocalDateTime.of(2026, 1, 2, 0, 0);
    stub(relation(USER_ID, PEER_ID, FriendStatus.NORMAL, mine, mine),
        relation(PEER_ID, USER_ID, FriendStatus.NORMAL, peers, peers));

    PrivateMessageAuthorizationSnapshot result = service.authorize(query());

    assertTrue(result.allowed());
    assertEquals("normal", result.relationStatus());
    assertEquals(epochMillis(peers), result.authorizationVersion(),
        "版本号取两侧关系的较大者，双方关系都变更才会推进");
  }

  @Test
  void versionFallsBackToCreatedAtWhenUpdatedAtIsAbsent() {
    // updatedAt 为空、createdAt 有值：版本必须回退到 createdAt（不是 0）。
    LocalDateTime createdAt = LocalDateTime.of(2026, 2, 1, 12, 0);
    stub(relation(USER_ID, PEER_ID, FriendStatus.NORMAL, createdAt, null),
        relation(PEER_ID, USER_ID, FriendStatus.NORMAL, createdAt, null));

    PrivateMessageAuthorizationSnapshot result = service.authorize(query());

    assertEquals(epochMillis(createdAt), result.authorizationVersion(),
        "updatedAt 缺失时必须回退到 createdAt——这条回退顺序是跨服务授权的语义，不得简化");
  }

  @Test
  void versionIsStableWhenRelationsDoNotChange() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 3, 1, 8, 30);
    LocalDateTime updatedAt = LocalDateTime.of(2026, 3, 2, 9, 0);
    Persisted<FriendRelation> mine = relation(USER_ID, PEER_ID, FriendStatus.NORMAL, createdAt, updatedAt);
    Persisted<FriendRelation> peers = relation(PEER_ID, USER_ID, FriendStatus.NORMAL, createdAt, updatedAt);
    stub(mine, peers);

    long first = service.authorize(query()).authorizationVersion();
    long second = service.authorize(query()).authorizationVersion();

    assertEquals(first, second, "关系未变更时版本必须稳定，否则缓存会被无谓击穿");
    assertEquals(epochMillis(updatedAt), first);
  }

  @Test
  void versionAdvancesWhenEitherSideChanges() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 4, 1, 0, 0);
    LocalDateTime earlier = LocalDateTime.of(2026, 4, 2, 0, 0);
    LocalDateTime later = LocalDateTime.of(2026, 4, 3, 0, 0);

    stub(relation(USER_ID, PEER_ID, FriendStatus.NORMAL, createdAt, earlier),
        relation(PEER_ID, USER_ID, FriendStatus.NORMAL, createdAt, earlier));
    long before = service.authorize(query()).authorizationVersion();

    // 仅一侧关系被更新（例如改了备注/分组），版本必须前移。
    stub(relation(USER_ID, PEER_ID, FriendStatus.NORMAL, createdAt, later),
        relation(PEER_ID, USER_ID, FriendStatus.NORMAL, createdAt, earlier));
    long after = service.authorize(query()).authorizationVersion();

    assertTrue(after > before, "任一侧关系变更后版本必须前移，否则已解除的授权不会失效");
    assertEquals(epochMillis(later), after);
  }

  @Test
  void blockedRelationDeniesButStillReportsVersion() {
    LocalDateTime moment = LocalDateTime.of(2026, 5, 1, 0, 0);
    stub(relation(USER_ID, PEER_ID, FriendStatus.NORMAL, moment, moment),
        relation(PEER_ID, USER_ID, FriendStatus.BLOCKED, moment, moment));

    PrivateMessageAuthorizationSnapshot result = service.authorize(query());

    assertFalse(result.allowed());
    // relationStatus 取的是「调用方自己那条关系」的状态，不是对端那条：
    // 对端 block 时本方仍是 normal，但 allowed=false 且 denialCode 指明原因。这是当前行为，测试如实钉住。
    assertEquals("normal", result.relationStatus());
    assertEquals("FRIEND_RELATION_NOT_ALLOWED", result.denialCode());
    assertEquals(epochMillis(moment), result.authorizationVersion(),
        "被拒绝时仍要带版本号，供调用方判断是否需要刷新已缓存的授权结果");
  }

  @Test
  void missingRelationDeniesWithZeroVersion() {
    when(repository.findByUserIdAndFriendId(USER_ID, PEER_ID)).thenReturn(Optional.empty());
    when(repository.findByUserIdAndFriendId(PEER_ID, USER_ID)).thenReturn(Optional.empty());

    PrivateMessageAuthorizationSnapshot result = service.authorize(query());

    assertFalse(result.allowed());
    assertEquals("none", result.relationStatus());
    assertEquals("FRIEND_RELATION_NOT_FOUND", result.denialCode());
    assertEquals(0L, result.authorizationVersion(), "关系不存在时版本为 0");
  }

  private PrivateMessageAuthorizationQuery query() {
    return new PrivateMessageAuthorizationQuery(USER_ID, PEER_ID, "PRIVATE", null);
  }

  private void stub(Persisted<FriendRelation> mine, Persisted<FriendRelation> peers) {
    when(repository.findByUserIdAndFriendId(USER_ID, PEER_ID)).thenReturn(Optional.of(mine));
    when(repository.findByUserIdAndFriendId(PEER_ID, USER_ID)).thenReturn(Optional.of(peers));
  }

  /** 领域模型不带审计列：测试直接组装「聚合 + 记账快照」，与适配器同职责。 */
  private static Persisted<FriendRelation> relation(long userId, long friendId, FriendStatus status,
      LocalDateTime createdAt, LocalDateTime updatedAt) {
    FriendRelation relation = FriendRelation.create(userId, friendId);
    relation.restore(1L, userId, friendId, "", "", status);
    return new Persisted<>(relation, new AuditSnapshot(null, createdAt, null, updatedAt));
  }

  private static long epochMillis(LocalDateTime moment) {
    return moment.toInstant(ZoneOffset.UTC).toEpochMilli();
  }
}
