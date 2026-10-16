package io.openware.platform.resource.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.sql.SqlScriptUtils;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.openware.platform.resource.domain.roomtype.model.RoomType;
import io.openware.platform.resource.domain.roomtype.repository.RoomTypeRepository;
import io.openware.platform.resource.infra.persistence.mapper.RoomTypeMapper;
import io.openware.platform.resource.infra.persistence.po.RoomTypePo;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link RoomTypeRepository} 的持久化适配器。
 *
 * <p>{@link #updateSubmitted} 用**显式 SET 列表**而不是 {@code updateById}：房型的「清空单价」写的是
 * {@code null}，而 MyBatis-Plus 默认 NOT_NULL 更新策略会把 null 字段从 UPDATE 中剔除，清空会静默失效。
 * 逐列 SET 让"提交了哪些列"成为显式事实。
 *
 * <p>{@code image_urls} 是 JSON 列：{@code set(...)} 的占位符按运行时参数类型找 TypeHandler，
 * {@code List} 没有内置处理器，必须显式带上与 {@link RoomTypePo#getImageUrls()} 同款的
 * {@link JacksonTypeHandler}，否则参数绑定会报「找不到 TypeHandler」。
 *
 * <p>审计字段由本层填充（res_room_type 的 created_at/updated_at 为 NOT NULL 无默认值）。
 */
@Repository
public class RoomTypeRepositoryAdapter implements RoomTypeRepository {

  private static final String IMAGE_URLS_TYPE_HANDLER =
      SqlScriptUtils.mappingTypeHandler(JacksonTypeHandler.class);

  private final RoomTypeMapper mapper;

  public RoomTypeRepositoryAdapter(RoomTypeMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<RoomType> listInStore(Long tenantId, Long storeId, String status) {
    return mapper.selectList(Wrappers.<RoomTypePo>lambdaQuery()
            .eq(RoomTypePo::getTenantId, tenantId)
            .eq(RoomTypePo::getStoreId, storeId)
            .eq(status != null, RoomTypePo::getStatus, status)
            .orderByAsc(RoomTypePo::getSortOrder)
            .orderByAsc(RoomTypePo::getId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<RoomType> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
  }

  @Override
  public List<RoomType> findByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return mapper.selectByIds(ids).stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<RoomType> findInStore(Long tenantId, Long storeId, Long roomTypeId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<RoomTypePo>lambdaQuery()
        .eq(RoomTypePo::getTenantId, tenantId)
        .eq(RoomTypePo::getStoreId, storeId)
        .eq(RoomTypePo::getId, roomTypeId))).map(this::toDomain);
  }

  @Override
  public RoomType insert(RoomType roomType) {
    LocalDateTime now = LocalDateTime.now();
    RoomTypePo po = new RoomTypePo();
    po.setTenantId(roomType.tenantId());
    po.setStoreId(roomType.storeId());
    po.setCode(roomType.code());
    po.setName(roomType.name());
    po.setCapacity(roomType.capacity());
    po.setImageUrls(roomType.imageUrls());
    po.setMainImageUrl(roomType.mainImageUrl());
    po.setUnitPrice(roomType.unitPrice());
    po.setServerUnitPrice(roomType.serverUnitPrice());
    po.setSortOrder(roomType.sortOrder());
    po.setStatus(roomType.status());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateSubmitted(RoomType roomType) {
    return mapper.update(null, Wrappers.<RoomTypePo>lambdaUpdate()
        .eq(RoomTypePo::getId, roomType.id())
        .set(RoomTypePo::getCode, roomType.code())
        .set(RoomTypePo::getName, roomType.name())
        .set(RoomTypePo::getCapacity, roomType.capacity())
        .set(RoomTypePo::getImageUrls, roomType.imageUrls(), IMAGE_URLS_TYPE_HANDLER)
        .set(RoomTypePo::getMainImageUrl, roomType.mainImageUrl())
        .set(RoomTypePo::getUnitPrice, roomType.unitPrice())
        .set(RoomTypePo::getServerUnitPrice, roomType.serverUnitPrice())
        .set(RoomTypePo::getSortOrder, roomType.sortOrder())
        .set(RoomTypePo::getStatus, roomType.status())
        .set(RoomTypePo::getUpdatedAt, LocalDateTime.now())) > 0;
  }

  @Override
  public boolean deleteById(Long id) {
    return mapper.deleteById(id) > 0;
  }

  @Override
  public long countByCode(Long tenantId, Long storeId, String code, Long excludedId) {
    return mapper.selectCount(Wrappers.<RoomTypePo>lambdaQuery()
        .eq(RoomTypePo::getTenantId, tenantId)
        .eq(RoomTypePo::getStoreId, storeId)
        .eq(RoomTypePo::getCode, code)
        .ne(excludedId != null, RoomTypePo::getId, excludedId));
  }

  @Override
  public long countByName(Long tenantId, Long storeId, String name, Long excludedId) {
    return mapper.selectCount(Wrappers.<RoomTypePo>lambdaQuery()
        .eq(RoomTypePo::getTenantId, tenantId)
        .eq(RoomTypePo::getStoreId, storeId)
        .eq(RoomTypePo::getName, name)
        .ne(excludedId != null, RoomTypePo::getId, excludedId));
  }

  private RoomType toDomain(RoomTypePo po) {
    return new RoomType(po.getId(), po.getTenantId(), po.getStoreId(), po.getCode(), po.getName(),
        po.getCapacity(), po.getImageUrls(), po.getMainImageUrl(), po.getUnitPrice(),
        po.getServerUnitPrice(), po.getSortOrder(), po.getStatus());
  }
}
