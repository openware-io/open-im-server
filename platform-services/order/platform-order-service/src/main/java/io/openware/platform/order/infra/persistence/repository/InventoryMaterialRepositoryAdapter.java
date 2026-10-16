package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.sql.SqlScriptUtils;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import io.openware.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import io.openware.platform.order.infra.persistence.po.InventoryMaterialPo;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link InventoryMaterialRepository} 的持久化适配器。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code ord_inventory_material}）：id / tenant_id / store_id /
 * material_code / name / category / unit / description / safety_stock / purchase_price / currency_code /
 * image_urls / main_image_url / status / version + 四个审计列。
 *
 * <p><b>非列字段绝不进入写入</b>：{@code InventoryMaterialPo} 上的 {@code onHandQty} / {@code reservedQty}
 * 标了 {@code @TableField(exist = false)}（DDL 里没有这两列），本适配器连设都不设——它们由列表读路径
 * 按物料 id 批量查库存余额后在视图层回填。给这类字段生成列引用会让 MyBatis-Plus 在**运行时**抛
 * {@code can not find lambda cache for this property [...]}，而 {@code test-compile} 完全干净
 * （钱包切片的 CAS 适配器正是这样把四条资金路径全部弄挂的）。
 *
 * <p><b>审计列的归属</b>：{@code created_by / updated_by / updated_at} 是记账信息，不是业务事实
 * （standards/10），领域模型没有它们。{@link #insert} 写 {@code now}；{@link #update} 刷新
 * {@code updated_at} 且不碰不可变的 {@code created_*}（显式列 UPDATE 天然不会写到它们）。
 *
 * <p><b>JSON 列的归属</b>：{@code image_urls} 是 {@code json} 列，PO 上用
 * {@code @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class)} 声明；
 * 领域模型只承载 {@code List<String>}。走实体写入时由 PO 的 TableInfo 解析处理器；
 * 走 {@code LambdaUpdateWrapper} 显式列更新时必须自己带上同一个处理器（{@link #ITEM_IMAGES_TYPE_HANDLER}），
 * 否则参数绑定阶段会报「找不到 TypeHandler」。
 */
@Repository
public class InventoryMaterialRepositoryAdapter implements InventoryMaterialRepository {

  /**
   * {@code LambdaUpdateWrapper.set(...)} 生成的 {@code #{}} 占位符按运行时参数类型找 TypeHandler，
   * {@code List} 没有内置处理器，必须显式带上与 PO 上同款的 {@link JacksonTypeHandler}。
   */
  private static final String ITEM_IMAGES_TYPE_HANDLER = SqlScriptUtils.mappingTypeHandler(JacksonTypeHandler.class);

  private final InventoryMaterialMapper mapper;

  public InventoryMaterialRepositoryAdapter(InventoryMaterialMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<InventoryMaterial> findById(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.selectById(id)).map(InventoryMaterialRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<InventoryMaterial> findInStore(Long tenantId, Long storeId, Long materialId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<InventoryMaterialPo>lambdaQuery()
        .eq(InventoryMaterialPo::getTenantId, tenantId)
        .eq(InventoryMaterialPo::getStoreId, storeId)
        .eq(InventoryMaterialPo::getId, materialId))).map(InventoryMaterialRepositoryAdapter::toDomain);
  }

  @Override
  public List<InventoryMaterial> findByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return mapper.selectByIds(ids).stream().map(InventoryMaterialRepositoryAdapter::toDomain).toList();
  }

  @Override
  public List<Long> findIdsByNameOrCode(Long tenantId, Long storeId, String nameOrCode) {
    // 只取主键：这张表的用途是「先拿 id 集合，再在流水/余额表上 IN」，不需要整行。
    return mapper.selectList(Wrappers.<InventoryMaterialPo>lambdaQuery()
            .eq(InventoryMaterialPo::getTenantId, tenantId)
            .eq(InventoryMaterialPo::getStoreId, storeId)
            .and(nameOrCode != null, wrapper -> wrapper
                .like(InventoryMaterialPo::getName, nameOrCode)
                .or().like(InventoryMaterialPo::getMaterialCode, nameOrCode))
            .select(InventoryMaterialPo::getId))
        .stream().map(InventoryMaterialPo::getId).filter(java.util.Objects::nonNull).toList();
  }

  @Override
  public Persisted<InventoryMaterial> insert(InventoryMaterial material) {
    InventoryMaterialPo po = toPo(material);
    if (po.getVersion() == null) {
      po.setVersion(0);
    }
    LocalDateTime now = LocalDateTime.now();
    // 审计时间在适配器兜底：调用方只表达「新建一个物料」，落库时刻是持久化口径。
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // created_by/updated_by 留空：DDL 是 NOT NULL DEFAULT 0（0 = 系统），迁移前的实现也没有写这两列。
    // 撞唯一键（门店内物料编码）必须抛出，不吞。
    mapper.insert(po);
    return persisted(po, now);
  }

  @Override
  public Persisted<InventoryMaterial> update(InventoryMaterial material) {
    LocalDateTime now = LocalDateTime.now();
    // 可编辑列**整组显式写出**：null 也要进 SET。
    // 迁移前的实现在这里用 LambdaUpdateWrapper 逐列 set（而不是 updateById），原因相同：
    // NOT_NULL 策略会把 null 整列跳过，「0 = 清空采购价」「清空描述」会静默失效。
    // 调用方已把「本次不改」解析成既有值，因此写出既有值与不写该列在语义上等价。
    LambdaUpdateWrapper<InventoryMaterialPo> update = new LambdaUpdateWrapper<InventoryMaterialPo>()
        .eq(InventoryMaterialPo::getId, material.id())
        .set(InventoryMaterialPo::getName, material.name())
        .set(InventoryMaterialPo::getCategory, material.category())
        .set(InventoryMaterialPo::getUnit, material.unit())
        .set(InventoryMaterialPo::getDescription, material.description())
        .set(InventoryMaterialPo::getSafetyStock, material.safetyStock())
        .set(InventoryMaterialPo::getPurchasePrice, material.purchasePrice())
        .set(InventoryMaterialPo::getCurrencyCode, material.currencyCode())
        .set(InventoryMaterialPo::getImageUrls, material.imageUrls(), ITEM_IMAGES_TYPE_HANDLER)
        .set(InventoryMaterialPo::getMainImageUrl, material.mainImageUrl())
        .set(InventoryMaterialPo::getUpdatedAt, now);
    // material_code / tenant_id / store_id / status / version / created_* 不在此列：它们不是可编辑列。
    mapper.update(null, update);
    InventoryMaterialPo written = toPo(material);
    // 记账列是不可变的：显式列 UPDATE 不会写到它们，这里读一次只为把原值交给响应视图
    // （迁移前是整行读回后再 updateById，响应里的 created_by/updated_by 就是库里的值）。
    InventoryMaterialPo existing = material.id() == null ? null : mapper.selectById(material.id());
    if (existing != null) {
      written.setCreatedBy(existing.getCreatedBy());
      written.setCreatedAt(existing.getCreatedAt());
      written.setUpdatedBy(existing.getUpdatedBy());
    }
    return persisted(written, now);
  }

  @Override
  public MaterialPage findPage(MaterialQuery query) {
    long total = mapper.selectCount(filterOf(query));
    if (total == 0) {
      // 命中为空不再查列表（与 MyBatis-Plus 分页「总数为 0 时不下发 LIMIT 查询」同一效果）。
      return new MaterialPage(List.of(), 0L);
    }
    // 偏移 + 上限由端口表达，这里翻译成 LIMIT/OFFSET；分页对象（Page/IPage）不进入端口签名。
    List<InventoryMaterial> materials = mapper.selectList(filterOf(query)
            .orderByDesc(InventoryMaterialPo::getId)
            .last("LIMIT " + Math.max(0L, query.limit()) + " OFFSET " + Math.max(0L, query.offset())))
        .stream().map(InventoryMaterialRepositoryAdapter::toDomain).toList();
    return new MaterialPage(materials, total);
  }

  /** 分页/计数的同一套过滤条件（计数与取页必须同口径，否则 total 与 records 会对不上）。 */
  private static LambdaQueryWrapper<InventoryMaterialPo> filterOf(MaterialQuery query) {
    return Wrappers.<InventoryMaterialPo>lambdaQuery()
        .eq(InventoryMaterialPo::getTenantId, query.tenantId())
        .eq(query.storeId() != null, InventoryMaterialPo::getStoreId, query.storeId())
        .eq(query.status() != null, InventoryMaterialPo::getStatus, query.status())
        .eq(query.category() != null, InventoryMaterialPo::getCategory, query.category())
        // 时间条件直接落在 created_at 列上，不用函数包裹（保持索引可用，口径与迁移前一致）。
        .ge(query.createdFrom() != null, InventoryMaterialPo::getCreatedAt, query.createdFrom())
        .le(query.createdTo() != null, InventoryMaterialPo::getCreatedAt, query.createdTo())
        .and(query.nameOrCode() != null, wrapper -> wrapper
            .like(InventoryMaterialPo::getName, query.nameOrCode())
            .or().like(InventoryMaterialPo::getMaterialCode, query.nameOrCode()));
  }

  /** 业务聚合 + 审计快照：分开交给上层，领域模型不承载记账字段。 */
  private static Persisted<InventoryMaterial> persisted(InventoryMaterialPo po, LocalDateTime updatedAt) {
    return new Persisted<>(toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), updatedAt));
  }

  /**
   * 领域模型 → 持久化对象（只写业务列；{@code onHandQty} / {@code reservedQty} 不是列，这里不设）。
   *
   * <p>{@code imageUrls} 原样交给 PO 上声明的 {@code JacksonTypeHandler} 序列化（见类注释）。
   */
  static InventoryMaterialPo toPo(InventoryMaterial material) {
    InventoryMaterialPo po = new InventoryMaterialPo();
    po.setId(material.id());
    po.setTenantId(material.tenantId());
    po.setStoreId(material.storeId());
    po.setMaterialCode(material.materialCode());
    po.setName(material.name());
    po.setCategory(material.category());
    po.setUnit(material.unit());
    po.setDescription(material.description());
    po.setSafetyStock(material.safetyStock());
    po.setPurchasePrice(material.purchasePrice());
    po.setCurrencyCode(material.currencyCode());
    po.setImageUrls(material.imageUrls());
    po.setMainImageUrl(material.mainImageUrl());
    po.setStatus(material.status());
    po.setVersion(material.version());
    po.setCreatedAt(material.createdAt());
    return po;
  }

  /** 持久化对象 → 领域模型（只取业务列；json 列由 JacksonTypeHandler 反序列化成 List）。 */
  static InventoryMaterial toDomain(InventoryMaterialPo po) {
    if (po == null) {
      return null;
    }
    return new InventoryMaterial(po.getId(), po.getTenantId(), po.getStoreId(), po.getMaterialCode(), po.getName(),
        po.getCategory(), po.getUnit(), po.getDescription(), po.getSafetyStock(), po.getPurchasePrice(),
        po.getCurrencyCode(), po.getImageUrls(), po.getMainImageUrl(), po.getStatus(), po.getVersion(),
        po.getCreatedAt());
  }
}
