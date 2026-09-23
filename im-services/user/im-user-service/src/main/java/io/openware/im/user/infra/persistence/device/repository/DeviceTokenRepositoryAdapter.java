package io.openware.im.user.infra.persistence.device.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.dto.PageResult;
import io.openware.common.enums.PushProvider;
import io.openware.im.user.domain.device.model.DeviceToken;
import io.openware.im.user.domain.device.repository.DeviceTokenRepository;
import io.openware.im.user.infra.persistence.device.converter.DeviceTokenPersistenceConverter;
import io.openware.im.user.infra.persistence.device.mapper.DeviceTokenMapper;
import io.openware.im.user.infra.persistence.device.po.DeviceTokenPo;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DeviceTokenRepositoryAdapter implements DeviceTokenRepository {
  private final DeviceTokenMapper mapper;

  @Override
  public Optional<DeviceToken> findByUserIdAndPushProviderAndToken(Long userId, PushProvider pushProvider,
      String token) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<DeviceTokenPo>lambdaQuery()
        .eq(DeviceTokenPo::getUserId, userId)
        .eq(DeviceTokenPo::getPushProvider, pushProvider)
        .eq(DeviceTokenPo::getToken, token))).map(DeviceTokenPersistenceConverter::toDomain);
  }

  @Override
  public List<DeviceToken> findEnabledByUserId(Long userId) {
    return mapper.selectList(Wrappers.<DeviceTokenPo>lambdaQuery()
            .eq(DeviceTokenPo::getUserId, userId)
            .eq(DeviceTokenPo::getEnabled, true))
        .stream().map(DeviceTokenPersistenceConverter::toDomain).toList();
  }

  @Override
  public List<DeviceToken> findEnabledByUserIds(Collection<Long> userIds) {
    return mapper.selectList(Wrappers.<DeviceTokenPo>lambdaQuery()
            .in(DeviceTokenPo::getUserId, userIds)
            .eq(DeviceTokenPo::getEnabled, true))
        .stream().map(DeviceTokenPersistenceConverter::toDomain).toList();
  }

  @Override
  public Optional<DeviceToken> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(DeviceTokenPersistenceConverter::toDomain);
  }

  @Override
  public PageResult<DeviceToken> findEnabledForAdmin(Long userId, int page, int pageSize) {
    Page<DeviceTokenPo> result = mapper.selectPage(new Page<>(page, pageSize),
        Wrappers.<DeviceTokenPo>lambdaQuery().eq(DeviceTokenPo::getEnabled, true)
            .eq(userId != null, DeviceTokenPo::getUserId, userId).orderByDesc(DeviceTokenPo::getUpdatedAt));
    return toPage(result, DeviceTokenPersistenceConverter::toDomain);
  }

  @Override
  public DeviceToken save(DeviceToken deviceToken) {
    DeviceTokenPo po = DeviceTokenPersistenceConverter.toPo(deviceToken);
    if (po.getId() == null) {
      mapper.insert(po);
      deviceToken.assignId(po.getId());
    } else {
      mapper.updateById(po);
    }
    return deviceToken;
  }

  private static <T> PageResult<T> toPage(Page<DeviceTokenPo> source,
      java.util.function.Function<DeviceTokenPo, T> converter) {
    return PageResult.<T>builder().items(source.getRecords().stream().map(converter).toList()).total(source.getTotal())
        .page((int) source.getCurrent()).pageSize((int) source.getSize()).build();
  }
}
