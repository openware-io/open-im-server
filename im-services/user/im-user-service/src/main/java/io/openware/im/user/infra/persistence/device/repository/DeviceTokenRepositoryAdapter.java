package io.openware.im.user.infra.persistence.device.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.dto.PageResult;
import io.openware.common.enums.PushProvider;
import io.openware.im.user.domain.device.model.DeviceToken;
import io.openware.im.user.domain.device.readmodel.DeviceTokenView;
import io.openware.im.user.domain.device.repository.AuditSnapshot;
import io.openware.im.user.domain.device.repository.Persisted;
import io.openware.im.user.domain.device.repository.DeviceTokenRepository;
import io.openware.im.user.infra.persistence.device.converter.DeviceTokenPersistenceConverter;
import io.openware.im.user.infra.persistence.device.mapper.DeviceTokenMapper;
import io.openware.im.user.infra.persistence.device.po.DeviceTokenPo;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.function.Function;

@Repository
@RequiredArgsConstructor
public class DeviceTokenRepositoryAdapter implements DeviceTokenRepository {
  private final DeviceTokenMapper mapper;

  @Override
  public Optional<Persisted<DeviceToken>> findByUserIdAndPushProviderAndToken(Long userId, PushProvider pushProvider,
      String token) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<DeviceTokenPo>lambdaQuery()
        .eq(DeviceTokenPo::getUserId, userId)
        .eq(DeviceTokenPo::getPushProvider, pushProvider)
        .eq(DeviceTokenPo::getToken, token))).map(this::toPersisted);
  }

  @Override
  public List<Persisted<DeviceToken>> findEnabledByUserId(Long userId) {
    return mapper.selectList(Wrappers.<DeviceTokenPo>lambdaQuery()
            .eq(DeviceTokenPo::getUserId, userId)
            .eq(DeviceTokenPo::getEnabled, true))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public List<Persisted<DeviceToken>> findEnabledByUserIds(Collection<Long> userIds) {
    return mapper.selectList(Wrappers.<DeviceTokenPo>lambdaQuery()
            .in(DeviceTokenPo::getUserId, userIds)
            .eq(DeviceTokenPo::getEnabled, true))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public Optional<Persisted<DeviceToken>> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toPersisted);
  }

  @Override
  public PageResult<DeviceTokenView> findEnabledForAdmin(Long userId, int page, int pageSize) {
    Page<DeviceTokenPo> result = mapper.selectPage(new Page<>(page, pageSize),
        Wrappers.<DeviceTokenPo>lambdaQuery().eq(DeviceTokenPo::getEnabled, true)
            .eq(userId != null, DeviceTokenPo::getUserId, userId).orderByDesc(DeviceTokenPo::getUpdatedAt));
    return toPage(result, this::toView);
  }

  @Override
  public Persisted<DeviceToken> save(DeviceToken deviceToken) {
    DeviceTokenPo po = DeviceTokenPersistenceConverter.toPo(deviceToken);
    // 审计列由适配器确定（standards/10 L299）：操作人恒为令牌所属用户。
    java.time.LocalDateTime now = java.time.LocalDateTime.now();
    po.setCreatedBy(po.getUserId());
    po.setUpdatedBy(po.getUserId());
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
      deviceToken.assignId(po.getId());
      return toPersisted(po);
    }
    po.setUpdatedAt(now);
    mapper.updateById(po);
    return toPersisted(mapper.selectById(po.getId()));
  }

  /** 业务聚合 + 记账快照：详情响应要回显审计列，而它们不进领域模型。 */
  private Persisted<DeviceToken> toPersisted(DeviceTokenPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(DeviceTokenPersistenceConverter.toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }

  /** 管理端列表投影：只读聚合查询，不参与状态变更（standards/10 L266-279）。 */
  private DeviceTokenView toView(DeviceTokenPo po) {
    return new DeviceTokenView(po.getId(), po.getUserId(), po.getToken(), po.getPushProvider(), po.getPlatform(),
        po.getDeviceId(), po.getEnabled(), po.getCreatedAt(), po.getUpdatedAt());
  }

  private static <T> PageResult<T> toPage(Page<DeviceTokenPo> source,
      Function<DeviceTokenPo, T> converter) {
    return PageResult.<T>builder().items(source.getRecords().stream().map(converter).toList()).total(source.getTotal())
        .page((int) source.getCurrent()).pageSize((int) source.getSize()).build();
  }
}
