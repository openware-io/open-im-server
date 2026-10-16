package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link CatalogItemRepository} 的持久化适配器。
 *
 * <p><b>审计列由本层负责</b>：{@code created_by / created_at / updated_by / updated_at} 是记账信息，
 * 不是业务事实（standards/10），因此领域模型没有它们。落库时刻在 {@link #insert} 里写
 * {@code now}、在 {@link #update} 里刷新 {@code updated_at}；{@code created_*} 是不可变列，
 * UPDATE 时从库里读回原值再原样带上（`NOT_NULL` 策略下不设这些列同样安全，
 * 但显式带上可以让生成的 SQL 与「只改业务列」的意图一致）。
 *
 * <p><b>JSON 列的归属</b>：{@code ord_catalog_item.image_urls} 是 {@code json} 列，
 * 持久化对象上用 {@code @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class)}
 * + {@code @TableName(autoResultMap = true)} 声明。序列化因此完全留在这里：领域模型
 * {@link CatalogItem} 只承载普通的 {@code List<String>}，应用层看不到 JSON、看不到
 * {@code TypeHandler}，也不需要（更不允许）自己拼 JSON 文本。
 *
 * <p>写入走**实体方法**（{@code insert}/{@code updateById}）而不是
 * {@code LambdaUpdateWrapper#set(po::getImageUrls, list)}：实体方法由 MyBatis-Plus 按
 * {@code TableInfo} 里声明的 typeHandler 绑定参数；而 Wrapper 路径是按运行时参数类型找
 * TypeHandler 的，{@code List} 没有内置处理器，必须显式带上
 * {@code SqlScriptUtils.mappingTypeHandler(JacksonTypeHandler.class)}——
 * 那会把 {@code com.baomidou...handler.JacksonTypeHandler} 与 {@code SqlScriptUtils} 拉进
 * 调用方（{@code InventoryApplicationService} 至今为此背着一个
 * {@code ITEM_IMAGES_TYPE_HANDLER} 常量）。实体方法没有这个代价。
 *
 * <p><b>非列字段绝不进入写入</b>：{@code CatalogItemPo} 上有三个
 * {@code @TableField(exist = false)} 字段。本适配器对它们<b>一个都不碰</b>：
 * {@code available} / {@code unavailableReason} / {@code availableQuantity} 由响应视图在装配期计算，
 * 领域模型里根本没有。给这类字段生成列引用会让 MyBatis-Plus 在运行时抛
 * {@code can not find lambda cache for this property [...]}
 * （钱包切片的 CAS 适配器栽过一次：{@code tokenAmount}/{@code tokenBrandName}/{@code accountOpened}）。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_catalog_item}）：
 * id / product_id / tenant_id / store_id / category / item_type / name / unit / unit_price /
 * description / image_urls / main_image_url / status / stock_controlled / sort_order +
 * created_by / created_at / updated_by / updated_at。
 */
@Repository
public class CatalogItemRepositoryAdapter implements CatalogItemRepository {

  private final CatalogItemMapper mapper;

  public CatalogItemRepositoryAdapter(CatalogItemMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<CatalogItem> findById(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.selectById(id)).map(CatalogItemRepositoryAdapter::toDomain);
  }

  @Override
  public List<CatalogItem> find(CatalogItemQuery query) {
    // activeOnly 与显式 status 互斥表达同一列：activeOnly 优先，避免同列出现两个相等条件。
    String status = query.activeOnly() ? CatalogItem.STATUS_ACTIVE : query.status();
    return mapper.selectList(Wrappers.<CatalogItemPo>lambdaQuery()
            .eq(CatalogItemPo::getTenantId, query.tenantId())
            .eq(status != null, CatalogItemPo::getStatus, status)
            .eq(query.category() != null, CatalogItemPo::getCategory, query.category())
            .eq(query.productId() != null, CatalogItemPo::getProductId, query.productId())
            // 门店口径：本门店 或 租户级通用（store_id IS NULL）；与原 LambdaQueryWrapper 的 and(...or().isNull(...)) 等价。
            .and(query.storeId() != null, wrapper -> wrapper
                .eq(CatalogItemPo::getStoreId, query.storeId())
                .or().isNull(CatalogItemPo::getStoreId))
            .orderByAsc(CatalogItemPo::getSortOrder)
            .orderByAsc(CatalogItemPo::getId))
        .stream().map(CatalogItemRepositoryAdapter::toDomain).toList();
  }

  @Override
  public Persisted<CatalogItem> insert(CatalogItem item) {
    CatalogItemPo po = toPo(item);
    LocalDateTime now = LocalDateTime.now();
    // 审计时间在适配器兜底：调用方只表达「新建一个目录项」，落库时刻是持久化口径。
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // created_by/updated_by 留空：DDL 是 NOT NULL DEFAULT 0（0 = 系统），既有的
    // CatalogController.doCreate 也没写这两列——保持同一落库口径，不新增审计列写入。
    // 撞唯一键/列约束必须抛出，不吞。
    mapper.insert(po);
    return persisted(po);
  }

  @Override
  public Persisted<CatalogItem> update(CatalogItem item) {
    CatalogItemPo existing = item.id() == null ? null : mapper.selectById(item.id());
    CatalogItemPo po = toPo(item);
    // created_at / created_by 是不可变列：从库里取回原值原样带上，避免依赖 NOT_NULL 策略的偶然行为。
    if (existing != null) {
      po.setCreatedAt(existing.getCreatedAt());
      po.setCreatedBy(existing.getCreatedBy());
      po.setUpdatedBy(existing.getUpdatedBy());
    }
    po.setUpdatedAt(LocalDateTime.now());
    // 实体更新（NOT_NULL 策略）：null 字段不写列，与迁移前的 updateById 逐列一致。
    // 非列字段不在此设置，因此不可能进入 SET 子句。
    mapper.updateById(po);
    return persisted(po);
  }

  /** 业务聚合 + 审计快照：分开交给上层，领域模型不承载记账字段。 */
  private static Persisted<CatalogItem> persisted(CatalogItemPo po) {
    return new Persisted<>(toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }

  @Override
  public int renameCategory(Long tenantId, Long storeId, String oldCategory, String newCategory) {
    // 单条条件 UPDATE：分类改名是批量改写，逐行读改写会产生 N 次写与并发覆盖。
    return mapper.update(null, Wrappers.<CatalogItemPo>lambdaUpdate()
        .eq(CatalogItemPo::getTenantId, tenantId)
        .eq(CatalogItemPo::getStoreId, storeId)
        .eq(CatalogItemPo::getCategory, oldCategory)
        .set(CatalogItemPo::getCategory, newCategory)
        .set(CatalogItemPo::getUpdatedAt, LocalDateTime.now()));
  }

  /**
   * 领域模型 → 持久化对象。
   *
   * <p>只写业务列；三个 {@code @TableField(exist = false)} 字段（available / unavailableReason /
   * availableQuantity）与领域模型无关，这里连设都不设。审计列由调用方（insert/update）按落库口径补齐。
   */
  static CatalogItemPo toPo(CatalogItem item) {
    CatalogItemPo po = new CatalogItemPo();
    po.setId(item.id());
    po.setProductId(item.productId());
    po.setTenantId(item.tenantId());
    po.setStoreId(item.storeId());
    po.setCategory(item.category());
    po.setItemType(item.itemType());
    po.setName(item.name());
    po.setUnit(item.unit());
    po.setUnitPrice(item.unitPrice());
    po.setDescription(item.description());
    // List<String> → json 列：由 PO 上声明的 JacksonTypeHandler 序列化（见类注释）。
    po.setImageUrls(item.imagesOrEmpty());
    po.setMainImageUrl(item.mainImageUrl());
    po.setStatus(item.status());
    po.setStockControlled(item.stockControlled());
    po.setSortOrder(item.sortOrder());
    return po;
  }

  /** 持久化对象 → 领域模型（只取业务列；json 列由 JacksonTypeHandler 反序列化成 List）。 */
  static CatalogItem toDomain(CatalogItemPo po) {
    if (po == null) {
      return null;
    }
    return new CatalogItem(po.getId(), po.getProductId(), po.getTenantId(), po.getStoreId(), po.getCategory(),
        po.getItemType(), po.getName(), po.getUnit(), po.getUnitPrice(), po.getDescription(), po.getImageUrls(),
        po.getMainImageUrl(), po.getStatus(), po.getStockControlled(), po.getSortOrder());
  }
}
