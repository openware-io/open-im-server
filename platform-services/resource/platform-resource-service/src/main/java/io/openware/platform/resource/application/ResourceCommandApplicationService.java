package io.openware.platform.resource.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.domain.resource.model.Resource;
import io.openware.platform.resource.domain.resource.repository.ResourceRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资源（包厢）创建/编辑编排。
 *
 * <p>权限、租户/门店上下文、编号唯一、房型校验、图片/描述归一、落库与审计都在应用服务完成；
 * 控制器只做协议转换（standards/10 §应用服务规则）。
 *
 * <p>清空语义必须由端口的具名方法表达（{@code clearRoomTypeBinding} / {@code updateDetails} 传空值）：
 * MyBatis-Plus 默认的 {@code FieldStrategy.NOT_NULL} 会把 null 列整列跳过，用 {@code save} 更新会出现
 * 「响应已清空、库里还在」的假成功——{@code PUT {"roomTypeId":0}} 解绑后 {@code DELETE} 房型永远 409
 * {@code ROOM_TYPE_IN_USE}。这是已修缺陷，不得退回。
 */
@Service
public class ResourceCommandApplicationService {
  /** 区域自由文本上限，与 res_resource.area_name 的 varchar(64) 一致。 */
  static final int MAX_AREA_NAME_LENGTH = 64;

  private final ResourceRepository resourceRepository;
  private final ResourceQueryApplicationService resourceQuery;
  private final RoomTypeApplicationService roomTypeService;
  private final AuditClient auditClient;

  /** 兼容既有单测/装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
  public ResourceCommandApplicationService(ResourceRepository resourceRepository,
                                           ResourceQueryApplicationService resourceQuery,
                                           RoomTypeApplicationService roomTypeService) {
    this(resourceRepository, resourceQuery, roomTypeService, AuditClient.disabled());
  }

  @Autowired
  public ResourceCommandApplicationService(ResourceRepository resourceRepository,
                                           ResourceQueryApplicationService resourceQuery,
                                           RoomTypeApplicationService roomTypeService,
                                           AuditClient auditClient) {
    this.resourceRepository = resourceRepository;
    this.resourceQuery = resourceQuery;
    this.roomTypeService = roomTypeService;
    this.auditClient = auditClient;
  }

  /**
   * 创建资源（ADM，KTV_ROOM/KTV_SERVER）。资源归属门店以运行上下文为准，请求里的门店必须一致。
   * 图片/描述与商品同规则：最多 9 张、主图必须属于列表（未指定时取第一张）、描述 ≤255。
   * 区域（areaName）≤64；房型（roomTypeId）必须属于本门店，否则 400；
   * roomTypeId 传 null 或 0 都表示「不指定房型」（与编辑清空同一语义）。
   */
  @Transactional
  public Resource create(CreateResourceCommand req) {
    try {
      PermissionGuard.require("resource.manage");
      TenantContext context = requireContext();
      // 只按上下文建资源：否则「在 A 门店上下文里勾选 B 门店」会被静默写成 A 门店的资源（数据错位）。
      if (req.storeId() != null && !context.storeId().equals(req.storeId())) {
        throw new ApiException(403, "STORE_SCOPE_DENIED", "所选门店与当前运营上下文不一致，请先切换门店上下文");
      }
      if (req.resourceType() == null || req.resourceType().isBlank()) {
        throw new ApiException(400, "RESOURCE_TYPE_REQUIRED", "缺少资源类型");
      }
      String resourceCode = req.resourceCode() == null ? "" : req.resourceCode().trim();
      String name = req.name() == null ? "" : req.name().trim();
      if (resourceCode.isBlank()) {
        throw new ApiException(400, "RESOURCE_CODE_REQUIRED", "缺少资源编号");
      }
      if (name.isBlank()) {
        throw new ApiException(400, "RESOURCE_NAME_REQUIRED", "缺少资源名称");
      }
      // 同门店同类型编号唯一：重复时给可读的 409，而不是把数据库唯一键冲突抛成 500。
      requireResourceCodeAvailable(context.storeId(), req.resourceType(), resourceCode, null);
      ResourceMedia.Images images = ResourceMedia.normalizeImages(req.imageUrls(), req.mainImageUrl());
      Resource created = resourceRepository.save(new Resource(null, context.tenantId(),
          context.storeId(), req.resourceType(), resourceCode, name,
          normalizeAreaName(req.areaName()), null, req.capacity(),
          resolveRoomTypeId(req.roomTypeId()), Resource.STATUS_ENABLED,
          Resource.CLEANING_IDLE, null, images.urls(), images.mainImageUrl(),
          ResourceMedia.normalizeDescription(req.description()), null, null, null, null));
      // 包厢（资源）新建/编辑直接影响开台计费与占用门禁，属「必须可回溯」的配置变更，写统一审计。
      auditClient.recordAsync(AuditClient.AuditRecord.builder()
          .action("resource.create")
          .resourceType("res_resource").resourceId(String.valueOf(created.id()))
          .resourceName(created.name())
          .idempotencyKey("resource-create:" + created.id())
          .detailJson("{\"storeId\":" + created.storeId() + ",\"resourceType\":" + jsonText(created.resourceType())
              + ",\"resourceCode\":" + jsonText(created.resourceCode()) + ",\"roomTypeId\":"
              + created.roomTypeId() + "}")
          .build());
      return enrich(created);
    } catch (RuntimeException failure) {
      // 失败出口留痕（缺权限/越权门店/编号冲突/房型非法/落库失败）：审计只 WARN，异常原样抛出。
      recordFailure("resource.create", req == null ? null : req.resourceCode(), failure);
      throw failure;
    }
  }

  /**
   * 编辑资源（ADM）：名称/区域/容量/房型/图片/描述。编号与类型不可改（避免同门店编号唯一键冲突与类型漂移）。
   * 按「提交才更新」处理：字段为 null 表示本次不动；图片与描述传空表示清空，areaName 传空白表示清空，
   * roomTypeId 传 0 表示清空房型（回到门店级单价），与创建时传 null/0 = 不指定房型保持一致。
   */
  @Transactional
  public Resource update(Long id, UpdateResourceCommand req) {
    try {
      return doUpdate(id, req);
    } catch (RuntimeException failure) {
      // 失败出口留痕（缺权限/越权门店/资源不存在/房型非法/落库失败）：审计只 WARN，异常原样抛出。
      recordFailure("resource.update", id == null ? null : String.valueOf(id), failure);
      throw failure;
    }
  }

  private Resource doUpdate(Long id, UpdateResourceCommand req) {
    PermissionGuard.require("resource.manage");
    TenantContext context = requireContext();
    Resource current = id == null ? null : resourceRepository.findById(id).orElse(null);
    if (current == null) {
      throw new ApiException(404, "RESOURCE_NOT_FOUND", "资源不存在或已被删除");
    }
    if (!context.storeId().equals(current.storeId())) {
      throw new ApiException(403, "STORE_SCOPE_DENIED", "该资源不属于当前门店上下文，请先切换门店");
    }
    // 审计留痕需要变更前后值：房型/区域/容量直接影响开台计费与占用，必须能回溯。
    String beforeName = current.name();
    String beforeAreaName = current.areaName();
    Integer beforeCapacity = current.capacity();
    String beforeRoomTypeId = current.roomTypeId() == null ? null : String.valueOf(current.roomTypeId());

    Resource updated = current;
    if (req.name() != null && !req.name().isBlank()) {
      updated = withName(updated, req.name().trim());
    }
    if (req.areaName() != null) {
      updated = withAreaName(updated, normalizeAreaName(req.areaName()));
    }
    if (req.capacity() != null) {
      updated = withCapacity(updated, req.capacity());
    }
    if (req.name() != null || req.areaName() != null || req.capacity() != null || req.description() != null) {
      String description = req.description() == null
          ? current.description() : ResourceMedia.normalizeDescription(req.description());
      // 空值同样进 SET（清空区域/描述必须真的落库为 NULL），这一步用显式列集，不能用整体保存。
      resourceRepository.updateDetails(id, updated.name(), updated.areaName(), updated.capacity(),
          description);
      updated = withDescription(updated, description);
    }
    if (req.roomTypeId() != null) {
      Long roomTypeId = resolveRoomTypeId(req.roomTypeId());
      // 解绑（roomTypeId 归一为 null）走具名端口方法，避免 NOT_NULL 策略把清空静默丢弃。
      if (roomTypeId == null) {
        resourceRepository.clearRoomTypeBinding(id);
      } else {
        resourceRepository.updateRoomTypeBinding(id, roomTypeId);
      }
      updated = withRoomTypeId(updated, roomTypeId);
    }
    if (req.imageUrls() != null) {
      ResourceMedia.Images images = ResourceMedia.normalizeImages(req.imageUrls(), req.mainImageUrl());
      resourceRepository.updateMedia(id, images.urls(), images.mainImageUrl());
      updated = withMedia(updated, images.urls(), images.mainImageUrl());
    }
    auditClient.recordAsync(AuditClient.AuditRecord.builder()
        .action("resource.update")
        .resourceType("res_resource").resourceId(String.valueOf(updated.id()))
        .resourceName(updated.name())
        .idempotencyKey("resource-update:" + updated.id() + ":" + System.currentTimeMillis())
        .detailJson("{\"before\":{\"name\":" + jsonText(beforeName) + ",\"areaName\":" + jsonText(beforeAreaName)
            + ",\"capacity\":" + beforeCapacity + ",\"roomTypeId\":" + rawJson(beforeRoomTypeId) + "},\"after\":{"
            + "\"name\":" + jsonText(updated.name()) + ",\"areaName\":" + jsonText(updated.areaName())
            + ",\"capacity\":" + updated.capacity() + ",\"roomTypeId\":"
            + rawJson(updated.roomTypeId() == null ? null : String.valueOf(updated.roomTypeId())) + "}}")
        .build());
    return enrich(updated);
  }

  /** 回填单个资源的房型快照（响应用）。 */
  private Resource enrich(Resource resource) {
    List<Resource> enriched = resourceQuery.enrichWithRoomTypes(List.of(resource));
    return enriched.isEmpty() ? resource : enriched.get(0);
  }

  /** 区域自由文本：空白归一为 null（未设置），超长 400。 */
  static String normalizeAreaName(String raw) {
    if (raw == null) {
      return null;
    }
    String area = raw.trim();
    if (area.isEmpty()) {
      return null;
    }
    if (area.length() > MAX_AREA_NAME_LENGTH) {
      throw new ApiException(400, "AREA_NAME_TOO_LONG", "区域名称长度不能超过 " + MAX_AREA_NAME_LENGTH + " 个字符");
    }
    return area;
  }

  /** 审计 detailJson 里的文本字段：null 落成 JSON null，其余加引号并转义，保证 detail 是合法 JSON。 */
  private static String jsonText(String raw) {
    if (raw == null) {
      return "null";
    }
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** 审计 detailJson 里的数值/可空字段（如 roomTypeId）：null 落成 JSON null，其余原样输出。 */
  private static String rawJson(String raw) {
    return raw == null ? "null" : raw;
  }

  /**
   * 房型入参归一（创建与更新同一语义）：{@code null} 或 {@code 0} 都表示「不指定 / 清空房型」，
   * 统一落库为 null（回退门店级单价）；其余值必须是本门店房型，否则 400。
   */
  private Long resolveRoomTypeId(Long roomTypeId) {
    if (roomTypeId == null || roomTypeId == 0L) {
      return null;
    }
    if (roomTypeService.findInStore(roomTypeId) == null) {
      throw new ApiException(400, "ROOM_TYPE_INVALID", "房型不存在或不属于当前门店，请重新选择");
    }
    return roomTypeId;
  }

  private TenantContext requireContext() {
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.tenantId() <= 0 || context.storeId() == null) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少有效的租户/门店上下文，请先在右上角选择门店");
    }
    return context;
  }

  private void requireResourceCodeAvailable(Long storeId, String resourceType, String resourceCode, Long excludeId) {
    long duplicated = resourceRepository.countByResourceCode(storeId, resourceType, resourceCode, excludeId);
    if (duplicated > 0) {
      throw new ApiException(409, "RESOURCE_CODE_EXISTS", "编号「" + resourceCode + "」已存在，请换一个编号");
    }
  }

  /**
   * 领域内失败留痕：包厢/资源写操作的失败出口此前完全没有留痕（BFF 拦截器只覆盖 BFF 路径），
   * 而资源变更直接决定开台计费与占用门禁，「改失败」同样必须可回溯。
   */
  private void recordFailure(String action, String resourceId, RuntimeException failure) {
    TenantContext context = TenantContextHolder.get();
    auditClient.recordAsync(AuditClient.AuditRecord.builder()
        .tenantId(context == null ? null : context.tenantId())
        .storeId(context == null ? null : context.storeId())
        .action(action)
        .resourceType("res_resource")
        .resourceId(resourceId)
        .result(AuditClient.AuditRecord.RESULT_FAILURE)
        .errorCode(AuditErrorCodes.of(failure))
        .build());
  }

  private static Resource withName(Resource r, String name) {
    return new Resource(r.id(), r.tenantId(), r.storeId(), r.resourceType(), r.resourceCode(), name,
        r.areaName(), r.parentId(), r.capacity(), r.roomTypeId(), r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), r.imageUrls(), r.mainImageUrl(), r.description(), r.roomTypeCode(),
        r.roomTypeName(), r.roomTypeUnitPrice(), r.roomTypeServerUnitPrice());
  }

  private static Resource withAreaName(Resource r, String areaName) {
    return new Resource(r.id(), r.tenantId(), r.storeId(), r.resourceType(), r.resourceCode(), r.name(),
        areaName, r.parentId(), r.capacity(), r.roomTypeId(), r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), r.imageUrls(), r.mainImageUrl(), r.description(), r.roomTypeCode(),
        r.roomTypeName(), r.roomTypeUnitPrice(), r.roomTypeServerUnitPrice());
  }

  private static Resource withCapacity(Resource r, Integer capacity) {
    return new Resource(r.id(), r.tenantId(), r.storeId(), r.resourceType(), r.resourceCode(), r.name(),
        r.areaName(), r.parentId(), capacity, r.roomTypeId(), r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), r.imageUrls(), r.mainImageUrl(), r.description(), r.roomTypeCode(),
        r.roomTypeName(), r.roomTypeUnitPrice(), r.roomTypeServerUnitPrice());
  }

  private static Resource withDescription(Resource r, String description) {
    return new Resource(r.id(), r.tenantId(), r.storeId(), r.resourceType(), r.resourceCode(), r.name(),
        r.areaName(), r.parentId(), r.capacity(), r.roomTypeId(), r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), r.imageUrls(), r.mainImageUrl(), description, r.roomTypeCode(),
        r.roomTypeName(), r.roomTypeUnitPrice(), r.roomTypeServerUnitPrice());
  }

  private static Resource withRoomTypeId(Resource r, Long roomTypeId) {
    // 清空房型时快照一并清掉，避免响应里残留上一次的房型名/单价。
    return new Resource(r.id(), r.tenantId(), r.storeId(), r.resourceType(), r.resourceCode(), r.name(),
        r.areaName(), r.parentId(), r.capacity(), roomTypeId, r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), r.imageUrls(), r.mainImageUrl(), r.description(),
        roomTypeId == null ? null : r.roomTypeCode(),
        roomTypeId == null ? null : r.roomTypeName(),
        roomTypeId == null ? null : r.roomTypeUnitPrice(),
        roomTypeId == null ? null : r.roomTypeServerUnitPrice());
  }

  private static Resource withMedia(Resource r, List<String> imageUrls, String mainImageUrl) {
    return new Resource(r.id(), r.tenantId(), r.storeId(), r.resourceType(), r.resourceCode(), r.name(),
        r.areaName(), r.parentId(), r.capacity(), r.roomTypeId(), r.status(), r.cleaningStatus(),
        r.cleaningStartedAt(), imageUrls, mainImageUrl, r.description(), r.roomTypeCode(),
        r.roomTypeName(), r.roomTypeUnitPrice(), r.roomTypeServerUnitPrice());
  }

  public record CreateResourceCommand(Long tenantId, Long storeId, String resourceType, String resourceCode,
                                      String name, String areaName, Integer capacity, Long roomTypeId,
                                      List<String> imageUrls, String mainImageUrl, String description) {
  }

  public record UpdateResourceCommand(String name, String areaName, Integer capacity, Long roomTypeId,
                                      List<String> imageUrls, String mainImageUrl, String description) {
  }
}
