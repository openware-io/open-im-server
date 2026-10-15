package io.openware.platform.resource.domain.resource.repository;

import io.openware.platform.resource.domain.resource.model.Resource;
import java.util.List;
import java.util.Optional;

/**
 * 资源仓储端口。
 *
 * <p>方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 *
 * <p>审计字段由适配器在 {@link #save} 内填充（res_resource 的 created_at/updated_at 为 NOT NULL 无默认值）。
 */
public interface ResourceRepository {

  /**
   * 按类型列出**启用中**的资源，可按门店限定。
   *
   * <p>「启用中」的口径是建表注释里的 {@code ENABLED}（非 ACTIVE），由适配器落实为条件，
   * 调用方不需要知道取值。
   *
   * @param storeId 门店；{@code null} 表示不限定门店（沿用历史行为）
   */
  List<Resource> listEnabledByType(String resourceType, Long storeId);

  /**
   * 管理列表口径：按类型/门店列出资源（**不限状态**，升序），可按**创建时间闭区间**过滤，并回填房型快照。
   *
   * <p>与 {@link #listEnabledByType}（可用性候选：仅 ENABLED、必填类型）和 {@link #listByType}
   * （内部端点：倒序）都不同，三者口径各自独立，不合并。
   *
   * @param fromInclusive 创建时间下界；{@code null} 表示不设
   * @param toInclusive   创建时间上界；{@code null} 表示不设
   */
  List<Resource> listInRange(String resourceType, Long storeId, java.time.LocalDateTime fromInclusive,
                             java.time.LocalDateTime toInclusive);

  /**
   * 按类型列出资源（**不限状态**，按标识倒序），类型与门店均可选。
   *
   * <p>与 {@link #listEnabledByType} 刻意分开：后者是「可用性候选」口径（仅 ENABLED、类型必填、升序），
   * 本方法是内部端点「按条件查看资源」口径（不限状态、类型可空、倒序）。把两者合成一个方法会让
   * 其中一处悄悄改变语义。
   */
  List<Resource> listByType(String resourceType, Long storeId);

  /** 按标识读取资源。 */
  Optional<Resource> findById(Long resourceId);

  /** 保存资源：{@code id} 为空时新增，否则更新，返回落库后的聚合。 */
  Resource save(Resource resource);

  /**
   * 更新基础信息列（名称、区域、容量、描述），返回是否确实更新了一行。
   *
   * <p>「编辑用例按提交才更新」的语义在这里落成分组方法，而不是传一列列名：调用方表达的是
   * 「这几类信息要改」，不是「往某几张列写值」。空值（如把区域清空）**必须真的写 NULL**——
   * 这正是不能用 {@code updateById} 的原因（NOT_NULL 策略会整列跳过，出现"只清了响应没清库"）。
   */
  boolean updateDetails(Long resourceId, String name, String areaName, Integer capacity, String description);

  /**
   * 更新图片列（列表 + 主图），返回是否确实更新了一行。
   *
   * <p>图片是 JSON 列；类型处理器归适配器，不在调用方出现。
   */
  boolean updateMedia(Long resourceId, java.util.List<String> imageUrls, String mainImageUrl);

  /**
   * 更新房型绑定，返回是否确实更新了一行。
   *
   * <p>{@code roomTypeId} 为 {@code null} 表示解绑。也可以用 {@link #clearRoomTypeBinding} 表达同一结果；
   * 二者并存是因为编辑用例需要"把房型改到另一个"与"清空"是同一列的一次写入。
   */
  boolean updateRoomTypeBinding(Long resourceId, Long roomTypeId);

  /**
   * 解绑房型（把 {@code room_type_id} 置为 NULL），返回是否确实更新了一行。
   *
   * <p><b>必须独立于 {@link #save}</b>，不能用"把 {@code roomTypeId} 置空后保存"表达：MyBatis-Plus 默认的
   * NOT_NULL 更新策略会把 null 字段从 UPDATE 中剔除，于是解绑**静默失效**——房型仍被引用，
   * 删除房型会一直 409 {@code ROOM_TYPE_IN_USE}。这是已修缺陷的根因（见 RoomTypeUnbindRegressionTest），
   * 因此该语义必须由端口显式承载，不得退回 save。
   *
   * <p>"清空这个字段"与"不动这个字段"是两种不同意图，端口必须能把二者区分开。
   */
  boolean clearRoomTypeBinding(Long resourceId);

  /** 统计仍引用该房型的资源数量（删除房型前的引用判据）。 */
  long countByRoomTypeId(Long roomTypeId);

  /**
   * 统计同门店同类型下使用该编号的资源数量（新增／修改前的编号唯一性判据）。
   *
   * <p>{@code excludedId} 非空时排除该行，供修改时排除自身。
   */
  long countByResourceCode(Long storeId, String resourceType, String resourceCode, Long excludedId);
}
