package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.platform.tenant.domain.pricing.model.PricingPlan;
import io.openware.platform.tenant.domain.pricing.repository.PricingPlanRepository;
import io.openware.platform.tenant.domain.pricing.service.PricingPlanResolver;
import io.openware.platform.tenant.domain.store.model.Store;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计价方案编排（创建幂等、乐观锁、作用域解析、门店范围校验）。
 *
 * <p>作用域优先级判定在 {@link PricingPlan#scopePrecedence}，解析在 {@link PricingPlanResolver}；
 * 本服务只做"取事实 → 交给领域 → 落库"的编排。
 *
 * <p><b>并发正确性</b>：冲突检测不由本层的"先读再比对版本"承担——那种写法在并发下会双双通过。
 * 真正的判定在 {@link PricingPlanRepository#updateWithVersion} 的单条 CAS 语句里，本层只负责把
 * CAS 失败翻译成 409。
 */
@Service
public class PricingPlanApplicationService {

  private static final String CODE_TENANT_SCOPE_FORBIDDEN = "TENANT_SCOPE_FORBIDDEN";
  private static final String CODE_STORE_SCOPE_FORBIDDEN = "STORE_SCOPE_FORBIDDEN";
  private static final String CODE_STORE_NOT_FOUND = "STORE_NOT_FOUND";
  private static final String CODE_BUSINESS_TYPE_MISMATCH = "BUSINESS_TYPE_MISMATCH";
  private static final String CODE_PRICING_PLAN_INVALID = "PRICING_PLAN_INVALID";
  private static final String CODE_VERSION_CONFLICT = "VERSION_CONFLICT";

  private final PricingPlanRepository pricingPlanRepository;
  private final StoreRepository storeRepository;

  public PricingPlanApplicationService(PricingPlanRepository pricingPlanRepository,
                                       StoreRepository storeRepository) {
    this.pricingPlanRepository = pricingPlanRepository;
    this.storeRepository = storeRepository;
  }

  /**
   * 新增或更新一条计价方案（按作用域四元组定位）。
   *
   * <p>优先级顺序刻意如此：
   * <ol>
   *   <li>入参形状（资源类型非空）；</li>
   *   <li>租户作用域（提交的 tenantId 必须等于上下文租户）；</li>
   *   <li>门店作用域（门店须属本租户，且业态一致——不一致宁可 422 也不要写出一条错业态的方案）；</li>
   *   <li><b>幂等键</b>（同键同资源类型直接返回既有方案，不重复写）；</li>
   *   <li>乐观锁 CAS。</li>
   * </ol>
   *
   * <p>幂等键排在作用域查询之后：调用方重试时既可能命中幂等键、也可能命中原作用域行，
   * 两者都应返回既有方案而不是报冲突。
   */
  @Transactional
  public PricingPlan save(Long contextTenantId, Long tenantId, Long storeId, String businessType,
                          String resourceType, String billingUnit, Integer incrementMinutes,
                          String roundingDirection, Long pricePerUnit, Integer defaultSessionMinutes,
                          BigDecimal overtimeRate, Integer version, String idempotencyKey) {
    if (resourceType == null || resourceType.isBlank()) {
      throw new ApiException(400, CODE_PRICING_PLAN_INVALID, "resourceType 不能为空");
    }
    if (tenantId == null || !contextTenantIdEquals(contextTenantId, tenantId)) {
      throw new ApiException(403, CODE_TENANT_SCOPE_FORBIDDEN, "租户作用域不匹配");
    }
    long effectiveStoreId = storeId == null ? PricingPlan.TENANT_LEVEL_STORE_ID : storeId;
    String effectiveBusinessType = normalize(businessType);
    if (effectiveStoreId > 0) {
      // 门店存活与业态一致性：两项都由门店事实决定，不接受提交值直接落库。
      Store store = requireOwnedStore(contextTenantId, effectiveStoreId);
      String actual = normalize(store.businessType());
      if (effectiveBusinessType != null && !effectiveBusinessType.equals(actual)) {
        throw new ApiException(422, CODE_BUSINESS_TYPE_MISMATCH, "业态与门店不匹配");
      }
      effectiveBusinessType = actual;
    }
    String scopeBusinessType = effectiveBusinessType == null ? PricingPlan.ANY_BUSINESS_TYPE : effectiveBusinessType;

    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      PricingPlan idem = pricingPlanRepository
          .findByIdempotencyKey(contextTenantId, idempotencyKey, resourceType).orElse(null);
      if (idem != null) {
        return idem;
      }
    }

    PricingPlan existing = pricingPlanRepository
        .findByScope(contextTenantId, effectiveStoreId, scopeBusinessType, resourceType).orElse(null);
    PricingPlan draft = new PricingPlan(existing == null ? null : existing.id(), contextTenantId,
        effectiveStoreId, scopeBusinessType, resourceType, billingUnit, incrementMinutes, roundingDirection,
        pricePerUnit, defaultSessionMinutes, overtimeRate, PricingPlan.STATUS_ACTIVE,
        existing == null ? 0 : existing.version(), idempotencyKey,
        existing == null ? null : existing.createdAt(), existing == null ? null : existing.updatedAt());

    if (existing == null) {
      return pricingPlanRepository.insert(draft);
    }
    // 客户端提交了版本时先做一次快速失败（可读的 409 而不是让 CAS 静默失败）；
    // 真正的并发判定仍由端口那条带 version 条件的 UPDATE 完成。
    if (version != null && !existing.versionMatches(version)) {
      throw versionConflict();
    }
    if (!pricingPlanRepository.updateWithVersion(draft, existing.version())) {
      throw versionConflict();
    }
    return draft;
  }

  /**
   * 批量创建/更新：先整批校验门店范围（归属/存在/业态一致），再逐店写入，整批共用同一个事务。
   *
   * <p>校验与编排留在应用服务（standards/10 §应用服务规则）：控制器只做协议转换，不持有
   * {@code @Transactional}。校验失败时不会留下“前半批落库、后半批失败”的半截配置。
   */
  @Transactional
  public List<PricingPlan> createBatch(long contextTenantId, PricingPlanBatchCommand command) {
    if (command == null || command.plan() == null || command.storeIds() == null || command.storeIds().isEmpty()) {
      throw new ApiException(400, "PRICING_BATCH_INVALID", "storeIds 和 plan 不能为空");
    }
    PricingPlanCommand plan = command.plan();
    StoreScopeValidation scope = validateStoreScope(contextTenantId, plan.businessType(), command.storeIds());
    List<PricingPlan> saved = new ArrayList<>();
    for (StoreScopeItem item : scope.stores()) {
      saved.add(save(contextTenantId, contextTenantId, item.storeId(), item.businessType(),
          plan.resourceType(), plan.billingUnit(), plan.incrementMinutes(), plan.roundingDirection(),
          plan.pricePerUnit(), plan.defaultSessionMinutes(), plan.overtimeRate(), plan.version(),
          command.idempotencyKey()));
    }
    return saved;
  }

  /** 列出某租户生效中的方案；给定门店时按作用域解析出每种资源类型的最终方案。 */
  @Transactional(readOnly = true)
  public List<PricingPlan> list(Long tenantId, Long storeId, String businessType) {    if (storeId == null) {
      return pricingPlanRepository.listActiveByTenant(tenantId, normalize(businessType));
    }
    Store store = requireOwnedStore(tenantId, storeId);
    String actual = normalize(store.businessType());
    String requested = normalize(businessType);
    if (requested != null && !requested.equals(actual)) {
      throw new ApiException(422, CODE_BUSINESS_TYPE_MISMATCH, "业态与门店不匹配");
    }
    return PricingPlanResolver.resolve(pricingPlanRepository.listActiveByTenant(tenantId), storeId, actual,
        List.of(PricingPlan.RESOURCE_KTV_ROOM, PricingPlan.RESOURCE_KTV_SERVER));
  }

  /**
   * 校验门店集合是否都属于本租户且业态一致，返回每个门店的实际业态。
   *
   * <p>一次遍历同时完成三项判定（归属、存在、业态一致）并收集结果：此前调用方要自己做多轮查询，
   * 容易在某条分支上漏掉归属检查。
   */
  @Transactional(readOnly = true)
  public StoreScopeValidation validateStoreScope(Long tenantId, String businessType, List<Long> storeIds) {
    if (storeIds == null || storeIds.isEmpty()) {
      throw new ApiException(400, "STORE_SCOPE_INVALID", "storeIds 不能为空");
    }
    String scopeBusinessType = normalize(businessType);
    List<StoreScopeItem> items = new ArrayList<>();
    for (Long storeId : storeIds) {
      if (storeId == null || storeId <= 0) {
        throw new ApiException(403, CODE_STORE_SCOPE_FORBIDDEN, "门店不属于当前租户");
      }
      Store store = requireOwnedStore(tenantId, storeId);
      String actual = normalize(store.businessType());
      if (scopeBusinessType != null && !scopeBusinessType.equals(actual)) {
        throw new ApiException(422, CODE_BUSINESS_TYPE_MISMATCH, "业态与门店不匹配");
      }
      if (scopeBusinessType == null) {
        scopeBusinessType = actual;
      }
      if (!scopeBusinessType.equals(actual)) {
        throw new ApiException(422, CODE_BUSINESS_TYPE_MISMATCH, "批量门店必须属于同一业态");
      }
      items.add(new StoreScopeItem(storeId, actual));
    }
    return new StoreScopeValidation(tenantId, scopeBusinessType, items);
  }

  /**
   * 门店归属校验：区分「不存在」与「属于别的租户」。
   *
   * <p>用 owner 查询而不是普通读取，才能给出 404 与 403 两种语义；直接把"不属于本租户"报成"不存在"
   * 会让调用方以为门店被删了。
   */
  private Store requireOwnedStore(Long tenantId, long storeId) {
    Long owner = storeRepository.findOwnerTenantId(storeId).orElse(null);
    if (owner == null) {
      throw new ApiException(404, CODE_STORE_NOT_FOUND, "门店不存在");
    }
    if (!owner.equals(tenantId)) {
      throw new ApiException(403, CODE_STORE_SCOPE_FORBIDDEN, "门店不属于当前租户");
    }
    return storeRepository.findById(storeId)
        .orElseThrow(() -> new ApiException(404, CODE_STORE_NOT_FOUND, "门店不存在"));
  }

  private static boolean contextTenantIdEquals(Long contextTenantId, Long tenantId) {
    return contextTenantId != null && contextTenantId.equals(tenantId);
  }

  private static ApiException versionConflict() {
    return new ApiException(409, CODE_VERSION_CONFLICT, "计价方案已被其他请求修改");
  }

  /** 业态归一为大写并去空白；空白视为"不限业态"（null）。 */
  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  /** 门店范围校验结果。 */
  public record StoreScopeValidation(Long tenantId, String businessType, List<StoreScopeItem> stores) {
  }

  /** 单个门店的范围校验结果。 */
  public record StoreScopeItem(Long storeId, String businessType) {
  }

  /** 批量写入的单方案命令（应用层类型，避免控制器 DTO 渗透到服务）。 */
  public record PricingPlanCommand(String businessType, String resourceType, String billingUnit,
                                   Integer incrementMinutes, String roundingDirection, Long pricePerUnit,
                                   Integer defaultSessionMinutes, BigDecimal overtimeRate, Integer version) {
  }

  /** 批量写入命令：方案 + 目标门店 + 幂等键。 */
  public record PricingPlanBatchCommand(PricingPlanCommand plan, List<Long> storeIds, String idempotencyKey) {
  }
}
