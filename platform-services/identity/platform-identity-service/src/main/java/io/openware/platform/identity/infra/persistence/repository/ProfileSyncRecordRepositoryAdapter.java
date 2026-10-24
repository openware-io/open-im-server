package io.openware.platform.identity.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.identity.domain.account.model.ProfileSyncRecord;
import io.openware.platform.identity.domain.account.repository.ProfileSyncRecordRepository;
import io.openware.platform.identity.infra.persistence.mapper.ProfileSyncRecordMapper;
import io.openware.platform.identity.infra.persistence.po.ProfileSyncRecordPo;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/**
 * {@link ProfileSyncRecordRepository} 的持久化适配器。
 *
 * <p>{@code occurredAt} 是业务时间，由领域模型携带并原样落库。{@code event_id} 允许为空
 * （建表注释：bind 拉取为空），bind 路径不传即可。
 *
 * <p><b>已知缺陷（既存，本次重构未改变其行为）</b>：{@code idt_profile_sync_record} 的建表语句包含
 * created_by/created_at/updated_by/updated_at 四个 NOT NULL 列，但持久化对象 {@code ProfileSyncRecordPo}
 * 并未映射它们，因此该表的 INSERT 在 NOT NULL 无默认值的列上缺值。本适配器保持与原服务一致的写法
 * （只写映射到的列），未擅自补列或改表：这属于需要单独判断的迁移问题，不夹带在分层重构里。
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
    LocalDateTime now = LocalDateTime.now();
    if (po.getId() == null) {
      po.setCreatedBy(0L);
      po.setCreatedAt(now);
      po.setUpdatedBy(0L);
      po.setUpdatedAt(now);
      mapper.insert(po);
    } else {
      po.setUpdatedBy(0L);
      po.setUpdatedAt(now);
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
