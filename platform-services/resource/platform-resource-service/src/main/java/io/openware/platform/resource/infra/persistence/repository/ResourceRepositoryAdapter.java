package io.openware.platform.resource.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.resource.domain.resource.model.Resource;
import io.openware.platform.resource.domain.resource.repository.ResourceRepository;
import io.openware.platform.resource.infra.persistence.mapper.ResourceMapper;
import io.openware.platform.resource.infra.persistence.po.ResourcePo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link ResourceRepository} 的持久化适配器。
 *
 * <p>「启用中」的口径在这里落成 {@code status = ENABLED}，与建表注释一致（不是 ACTIVE）。
 *
 * <p>审计字段由本层填充（res_resource 的 created_at/updated_at 为 NOT NULL 无默认值）。
 * {@code imageUrls} 由 MyBatis 类型处理器在 PO 上完成 JSON 往返，领域模型只看到有序图片列表。
 *
 * <p>房型快照字段（roomTypeCode/Name/UnitPrice/ServerUnitPrice）在 PO 上标注为不存在的列
 * （{@code @TableField(exist = false)}），由查询或上层填充；本适配器按 PO 当前值原样带出，
 * 不做额外 join——保持既有行为不变。
 */
@Repository
public class ResourceRepositoryAdapter implements ResourceRepository {

  private final ResourceMapper mapper;

  public ResourceRepositoryAdapter(ResourceMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<Resource> listEnabledByType(String resourceType, Long storeId) {
    return mapper.selectList(Wrappers.<ResourcePo>lambdaQuery()
            .eq(ResourcePo::getResourceType, resourceType)
            .eq(ResourcePo::getStatus, Resource.STATUS_ENABLED)
            .eq(storeId != null, ResourcePo::getStoreId, storeId)
            .orderByAsc(ResourcePo::getId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<Resource> findById(Long resourceId) {
    return Optional.ofNullable(mapper.selectById(resourceId)).map(this::toDomain);
  }

  @Override
  public Resource save(Resource resource) {
    LocalDateTime now = LocalDateTime.now();
    ResourcePo po = toPo(resource);
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
    } else {
      po.setUpdatedAt(now);
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  /**
   * 解绑房型：用显式 SET 把 {@code room_type_id} 置为 NULL。
   *
   * <p>不能用"把字段置空后 updateById"：MyBatis-Plus 默认 NOT_NULL 更新策略会把 null 字段从 UPDATE 中
   * 剔除，解绑会静默失效（这正是本方法必须独立存在的原因，见端口契约）。
   */
  @Override
  public boolean clearRoomTypeBinding(Long resourceId) {
    return mapper.update(null, Wrappers.<ResourcePo>lambdaUpdate()
        .eq(ResourcePo::getId, resourceId)
        .set(ResourcePo::getRoomTypeId, null)
        .set(ResourcePo::getUpdatedAt, LocalDateTime.now())) > 0;
  }

  @Override
  public long countByRoomTypeId(Long roomTypeId) {
    return mapper.selectCount(Wrappers.<ResourcePo>lambdaQuery()
        .eq(ResourcePo::getRoomTypeId, roomTypeId));
  }

  @Override
  public long countByResourceCode(Long storeId, String resourceType, String resourceCode, Long excludedId) {
    return mapper.selectCount(Wrappers.<ResourcePo>lambdaQuery()
        .eq(ResourcePo::getStoreId, storeId)
        .eq(ResourcePo::getResourceType, resourceType)
        .eq(ResourcePo::getResourceCode, resourceCode)
        .ne(excludedId != null, ResourcePo::getId, excludedId));
  }

  private ResourcePo toPo(Resource resource) {
    ResourcePo po = new ResourcePo();
    po.setId(resource.id());
    po.setTenantId(resource.tenantId());
    po.setStoreId(resource.storeId());
    po.setResourceType(resource.resourceType());
    po.setResourceCode(resource.resourceCode());
    po.setName(resource.name());
    po.setAreaName(resource.areaName());
    po.setParentId(resource.parentId());
    po.setCapacity(resource.capacity());
    po.setRoomTypeId(resource.roomTypeId());
    po.setStatus(resource.status());
    po.setCleaningStatus(resource.cleaningStatus());
    po.setCleaningStartedAt(resource.cleaningStartedAt());
    po.setImageUrls(resource.imageUrls());
    po.setMainImageUrl(resource.mainImageUrl());
    po.setDescription(resource.description());
    return po;
  }

  private Resource toDomain(ResourcePo po) {
    return new Resource(po.getId(), po.getTenantId(), po.getStoreId(), po.getResourceType(),
        po.getResourceCode(), po.getName(), po.getAreaName(), po.getParentId(), po.getCapacity(),
        po.getRoomTypeId(), po.getStatus(), po.getCleaningStatus(), po.getCleaningStartedAt(),
        po.getImageUrls(), po.getMainImageUrl(), po.getDescription(), po.getRoomTypeCode(),
        po.getRoomTypeName(), po.getRoomTypeUnitPrice(), po.getRoomTypeServerUnitPrice());
  }
}
