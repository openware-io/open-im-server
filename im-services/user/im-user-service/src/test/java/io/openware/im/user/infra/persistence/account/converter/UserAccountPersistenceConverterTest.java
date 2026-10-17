package io.openware.im.user.infra.persistence.account.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.openware.im.user.domain.account.model.UserAccountRole;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.readmodel.UserAccountView;
import io.openware.im.user.infra.persistence.account.po.UserAccountPo;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 管理端用户列表投影的映射：展示字段与审计列一一对应，密码哈希不进入投影。 */
class UserAccountPersistenceConverterTest {

  @Test
  void shouldCarryDisplayAndAuditColumnsIntoTheView() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 22, 8, 30);
    LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 22, 8, 31);
    UserAccountPo po = new UserAccountPo();
    po.setId(11L);
    po.setUsername("user-11");
    po.setNickname("昵称十一");
    po.setAvatar("avatar-11");
    po.setPassword("password-hash-must-not-leak");
    po.setEmail("user11@example.com");
    po.setPhone("13800000011");
    po.setSignature("signature");
    po.setStatus("active");
    po.setStatusVersion(3L);
    po.setRole("user");
    po.setCreatedBy(0L);
    po.setCreatedAt(createdAt);
    po.setUpdatedBy(99L);
    po.setUpdatedAt(updatedAt);

    UserAccountView view = UserAccountPersistenceConverter.toView(po);

    assertEquals(11L, view.id());
    assertEquals("user-11", view.username());
    assertEquals("昵称十一", view.nickname());
    assertEquals("avatar-11", view.avatar());
    assertEquals("user11@example.com", view.email());
    assertEquals("13800000011", view.phone());
    assertEquals("signature", view.signature());
    assertEquals(UserAccountStatus.ACTIVE, view.status());
    assertEquals(3L, view.statusVersion());
    assertEquals(UserAccountRole.USER, view.role());
    assertEquals(createdAt, view.createdAt());
    assertEquals(updatedAt, view.updatedAt());
  }
}
