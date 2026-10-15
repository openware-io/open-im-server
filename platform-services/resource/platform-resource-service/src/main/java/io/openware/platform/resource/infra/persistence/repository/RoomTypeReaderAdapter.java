package io.openware.platform.resource.infra.persistence.repository;

import io.openware.platform.resource.domain.roomtype.model.RoomType;
import io.openware.platform.resource.domain.roomtype.repository.RoomTypeReader;
import io.openware.platform.resource.infra.persistence.mapper.RoomTypeMapper;
import io.openware.platform.resource.infra.persistence.po.RoomTypePo;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link RoomTypeReader} 的持久化适配器。
 *
 * <p>{@code imageUrls} 的 JSON 列往返由 PO 上的 MyBatis 类型处理器完成，适配器只做模型映射。
 */
@Repository
public class RoomTypeReaderAdapter implements RoomTypeReader {

  private final RoomTypeMapper mapper;

  public RoomTypeReaderAdapter(RoomTypeMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<RoomType> findById(Long roomTypeId) {
    return Optional.ofNullable(mapper.selectById(roomTypeId)).map(this::toDomain);
  }

  @Override
  public List<RoomType> findByIds(List<Long> roomTypeIds) {
    if (roomTypeIds == null || roomTypeIds.isEmpty()) {
      return List.of();
    }
    return mapper.selectBatchIds(roomTypeIds).stream().map(this::toDomain).toList();
  }

  private RoomType toDomain(RoomTypePo po) {
    return new RoomType(po.getId(), po.getTenantId(), po.getStoreId(), po.getCode(), po.getName(),
        po.getCapacity(), po.getImageUrls(), po.getMainImageUrl(), po.getUnitPrice(),
        po.getServerUnitPrice(), po.getSortOrder(), po.getStatus());
  }
}
