package io.openware.platform.resource.application;

import io.openware.platform.resource.domain.resource.model.Resource;
import io.openware.platform.resource.domain.resource.repository.ResourceRepository;
import io.openware.platform.resource.domain.roomtype.model.RoomType;
import io.openware.platform.resource.domain.roomtype.repository.RoomTypeReader;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资源查询用例：读取资源并回填房型快照（编码/名称/单价）。
 *
 * <p>房型快照是 {@code res_resource} 上**不存在的列**（建表无此列），由本用例按 {@code room_type_id}
 * 批量回填，避免逐行查询；它参与对外展示与「按房型定价」，因此属于资源的读模型，而不是持久化字段。
 *
 * <p>此前这段逻辑同时存在于 {@code ResourceController} 与 {@code InternalResourceController}，
 * 且两者各自持有持久化 mapper。收进应用服务后，两个入站适配只需委派，房型读取也只经领域端口。
 */
@Service
public class ResourceQueryApplicationService {

  private final ResourceRepository resourceRepository;
  private final RoomTypeReader roomTypeReader;

  public ResourceQueryApplicationService(ResourceRepository resourceRepository,
                                        RoomTypeReader roomTypeReader) {
    this.resourceRepository = resourceRepository;
    this.roomTypeReader = roomTypeReader;
  }

  /**
   * 按类型列出启用中的资源（按标识倒序），并回填房型快照。
   *
   * @param storeId 门店；{@code null} 表示不限定
   */
  public List<Resource> listEnabled(String resourceType, Long storeId) {
    return enrichWithRoomTypes(resourceRepository.listEnabledByType(resourceType, storeId));
  }

  /** 按标识读取资源并回填房型快照；不存在返回 {@code null}，由调用方决定错误码。 */
  public Resource findById(Long resourceId) {
    Resource resource = resourceId == null ? null : resourceRepository.findById(resourceId).orElse(null);
    if (resource == null) {
      return null;
    }
    return applyRoomType(resource, roomType(resource.roomTypeId()));
  }

  /**
   * 批量回填房型快照：一次性按 id 取房型，避免逐行查询。
   *
   * <p>未绑定房型或房型已不存在时，快照字段保持为空——由前端按「未设置」展示。
   */
  public List<Resource> enrichWithRoomTypes(List<Resource> resources) {
    if (resources == null || resources.isEmpty()) {
      return resources == null ? List.of() : resources;
    }
    Set<Long> roomTypeIds = resources.stream()
        .map(Resource::roomTypeId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
    if (roomTypeIds.isEmpty()) {
      return resources;
    }
    Map<Long, RoomType> byId = roomTypeReader.findByIds(List.copyOf(roomTypeIds)).stream()
        .collect(Collectors.toMap(RoomType::id, Function.identity(), (first, second) -> first));
    return resources.stream()
        .map(resource -> applyRoomType(resource, byId.get(resource.roomTypeId())))
        .toList();
  }

  private RoomType roomType(Long roomTypeId) {
    return roomTypeId == null ? null : roomTypeReader.findById(roomTypeId).orElse(null);
  }

  /** 把房型快照写进资源的只读列；房型为空时原样返回（快照保持为空）。 */
  public static Resource applyRoomType(Resource resource, RoomType roomType) {
    if (resource == null || roomType == null) {
      return resource;
    }
    return new Resource(resource.id(), resource.tenantId(), resource.storeId(), resource.resourceType(),
        resource.resourceCode(), resource.name(), resource.areaName(), resource.parentId(),
        resource.capacity(), resource.roomTypeId(), resource.status(), resource.cleaningStatus(),
        resource.cleaningStartedAt(), resource.imageUrls(), resource.mainImageUrl(),
        resource.description(), roomType.code(), roomType.name(), roomType.unitPrice(),
        roomType.serverUnitPrice());
  }
}
