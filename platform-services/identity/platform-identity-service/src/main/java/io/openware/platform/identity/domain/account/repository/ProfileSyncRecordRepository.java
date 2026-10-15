package io.openware.platform.identity.domain.account.repository;

import io.openware.platform.identity.domain.account.model.ProfileSyncRecord;

/**
 * 资料同步记录仓储端口。
 *
 * <p>{@link #existsByEventId} 是消费者幂等的权威判据：事件重复投递时必须返回 {@code true}，
 * 据此跳过重复处理。该判据由数据库在 {@code event_id} 上的唯一性约束保证，不得仅依赖应用层先查后插。
 */
public interface ProfileSyncRecordRepository {

  /** 该事件是否已处理过（消费者幂等判据）。 */
  boolean existsByEventId(String eventId);

  /** 记录一次资料同步。 */
  ProfileSyncRecord save(ProfileSyncRecord record);
}
