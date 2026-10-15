package io.openware.platform.admin.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.admin.domain.menu.readmodel.AdminMenuRow;
import io.openware.platform.admin.domain.menu.repository.AdminMenuRepository;
import io.openware.platform.admin.infra.persistence.mapper.AdminMenuMapper;
import io.openware.platform.admin.infra.persistence.po.AdminMenuPo;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@link AdminMenuRepository} 的持久化适配器。
 *
 * <p>只读：只做投影映射与排序下推，不承载任何授权判定——{@code requiredPermission} / {@code requiredGrant}
 * 只是查询出来的声明，裁决权属于 tenant-IAM。
 */
@Repository
public class AdminMenuRepositoryAdapter implements AdminMenuRepository {

  /** 可用状态取值，与建表口径一致。 */
  private static final String STATUS_ACTIVE = "ACTIVE";

  private final AdminMenuMapper mapper;

  public AdminMenuRepositoryAdapter(AdminMenuMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<AdminMenuRow> findActiveOrdered() {
    return mapper.selectList(Wrappers.<AdminMenuPo>lambdaQuery()
            .eq(AdminMenuPo::getStatus, STATUS_ACTIVE)
            .orderByAsc(AdminMenuPo::getScopeLevel, AdminMenuPo::getSortNo, AdminMenuPo::getId))
        .stream().map(this::toProjection).toList();
  }

  private AdminMenuRow toProjection(AdminMenuPo po) {
    return new AdminMenuRow(po.getId(), po.getParentId(), po.getCode(), po.getName(), po.getPath(),
        po.getIcon(), po.getScopeLevel(), po.getDomainCode(), po.getRequiredPermission(),
        po.getRequiredGrant(), po.getBusinessType(), po.getI18nKey());
  }
}
