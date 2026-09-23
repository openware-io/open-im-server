package io.openware.im.admin.domain.repository;

import io.openware.im.admin.domain.miniapp.MiniappServiceItem;
import io.openware.im.admin.domain.miniapp.MiniappServiceType;
import java.util.List;
import java.util.Optional;

public interface MiniappRepository {
  List<MiniappServiceType> findAllTypes();

  Optional<MiniappServiceType> findTypeById(Integer id);

  MiniappServiceType saveType(MiniappServiceType type);

  void deleteType(Integer id);

  List<MiniappServiceItem> findPublishedConsumerItems();

  /**
   * 全部已发布服务项（含 operator 受众）。
   *
   * <p>「服务板块」下的运营端入口（如 A380后台 /b/）以 audience=operator 登记，此前公开接口
   * 只下发 consumer，导致 App 永远看不到这些入口（2026-09-10 修复）。运营权限由目标 H5
   * 自身校验（无权限时展示「未开通运营权限」），列表层不再过滤。
   */
  List<MiniappServiceItem> findPublishedItems();

  List<MiniappServiceItem> searchPublishedItems(String keyword);

  List<MiniappServiceItem> findItemsByType(Integer typeId, int offset, int pageSize);

  long countItemsByType(Integer typeId);

  Optional<MiniappServiceItem> findItemById(Integer id);

  MiniappServiceItem saveItem(MiniappServiceItem item);

  void deleteItem(Integer id);
}
