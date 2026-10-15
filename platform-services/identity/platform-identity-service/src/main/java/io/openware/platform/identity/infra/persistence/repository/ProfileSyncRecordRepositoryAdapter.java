package io.openware.platform.identity.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.identity.domain.account.model.ProfileSyncRecord;
import io.openware.platform.identity.domain.account.repository.ProfileSyncRecordRepository;
import io.openware.platform.identity.infra.persistence.mapper.ProfileSyncRecordMapper;
import io.openware.platform.identity.infra.persistence.po.ProfileSyncRecordPo;
import org.springframework.stereotype.Repository;

/**
 * {@link ProfileSyncRecordRepository} 的持久化适配器。
 *
 * <p>{@code occurredAt} 是业务时间，由领域模型携带并原样落库；本表没有 created_by/at 与 updated_by/at
 * 记账列（见 idt_profile_sync_record 建表），因此无需填充审计字段。
 *
 * <p>幂等判据由 {@code event_id} 上的唯一性约束保证：{@link #existsByEventId} 是查询视图，
 * 真正防重复依赖插入时的唯一键冲突，调用方需处理该冲突。
 */
@Repository
public class ProfileSyncRecordRepositoryAdapter implements ProfileSyncRecordRepository {

  private final ProfileSyncRecordMapper mapper;

  public ProfileSyncRecordRepositoryAdapter(ProfileSyncRecordMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean existsByEventId(String eventId) {
    return mapper.selectCount(new LambdaQueryWrapper<ProfileSyncRecordPo>()
        .eq(ProfileSyncRecordPo::getEventId, eventId)) > 0;
  }

  @Override
  public ProfileSyncRecord save(ProfileSyncRecord record) {
    ProfileSyncRecordPo po = new ProfileSyncRecordPo();
    po.setId(record.id());
    po.setEventId(record.eventId());
    po.setAccountId(record.accountId());
    po.setProvider(record.provider());
    po.setProfileJson(record.profileJson());
    po.setOccurredAt(record.occurredAt());
    if (po.getId() == null) {
      mapper.insert(po);
    } else {
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  private ProfileSyncRecord toDomain(ProfileSyncRecordPo po) {
    if (po == null) {
      return null;
    }
    return new ProfileSyncRecord(po.getId(), po.getEventId(), po.getAccountId(),
        po.getProvider(), po.getProfileJson(), po.getOccurredAt());
  }
}
