package io.openware.im.user.infra.persistence.account.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.repository.Persisted;
import io.openware.im.user.infra.persistence.account.converter.UserAccountPersistenceConverter;
import io.openware.im.user.infra.persistence.account.mapper.UserAccountMapper;
import io.openware.im.user.infra.persistence.account.po.UserAccountPo;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 账号写入的记账列契约：新增写全四个审计列；普通更新只写 updated_at
 * （created_* 与 updated_by 必须保持 null，让 MyBatis 的 NOT_NULL 策略跳过它们）；
 * 状态 CAS 显式写 updated_by/updated_at。
 */
class UserAccountRepositoryAdapterTest {

  @Test
  void insertShouldStampAllAuditColumns() {
    UserAccountMapper mapper = mock(UserAccountMapper.class);
    UserAccountRepositoryAdapter adapter = new UserAccountRepositoryAdapter(mapper);
    LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 22, 8, 30);
    UserAccount account = UserAccount.register("user", "hash", "昵称", "a@b.com", "13800000000", occurredAt);
    when(mapper.insert(any(UserAccountPo.class))).thenAnswer(invocation -> {
      invocation.getArgument(0, UserAccountPo.class).setId(11L);
      return 1;
    });

    Persisted<UserAccount> saved = adapter.save(account, occurredAt);

    ArgumentCaptor<UserAccountPo> captor = ArgumentCaptor.forClass(UserAccountPo.class);
    verify(mapper).insert(captor.capture());
    UserAccountPo po = captor.getValue();
    assertEquals(0L, po.getCreatedBy());
    assertEquals(occurredAt, po.getCreatedAt());
    assertEquals(0L, po.getUpdatedBy());
    assertEquals(occurredAt, po.getUpdatedAt());
    assertEquals(11L, saved.aggregate().getId());
    assertEquals(occurredAt, saved.audit().createdAt());
  }

  @Test
  void updateShouldOnlyWriteUpdatedAtAndPreserveUpdatedBy() {
    UserAccountMapper mapper = mock(UserAccountMapper.class);
    UserAccountRepositoryAdapter adapter = new UserAccountRepositoryAdapter(mapper);
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 22, 8, 30);
    LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 22, 9, 0);
    UserAccount account = UserAccount.register("user", "hash", "昵称", "a@b.com", "13800000000", createdAt);
    account.assignId(11L);
    account.updateProfile("新昵称", null, null, null, null);
    UserAccountPo existing = UserAccountPersistenceConverter.toPo(account);
    existing.setId(11L);
    existing.setCreatedBy(1L);
    existing.setCreatedAt(createdAt);
    existing.setUpdatedBy(2L);
    existing.setUpdatedAt(occurredAt);
    when(mapper.selectById(11L)).thenReturn(existing);

    Persisted<UserAccount> saved = adapter.save(account, occurredAt);

    ArgumentCaptor<UserAccountPo> captor = ArgumentCaptor.forClass(UserAccountPo.class);
    verify(mapper).updateById(captor.capture());
    UserAccountPo po = captor.getValue();
    assertNull(po.getCreatedBy(), "created_by 必须保持 null，避免覆盖库中原值");
    assertNull(po.getCreatedAt(), "created_at 必须保持 null，避免覆盖库中原值");
    assertNull(po.getUpdatedBy(), "普通更新不得改写 updated_by");
    assertEquals(occurredAt, po.getUpdatedAt());
    assertEquals(2L, saved.audit().updatedBy());
  }

  @Test
  void statusChangeShouldWriteOperatorAndTimeUnderVersionCondition() {
    UserAccountMapper mapper = mock(UserAccountMapper.class);
    UserAccountRepositoryAdapter adapter = new UserAccountRepositoryAdapter(mapper);
    LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 22, 9, 0);
    UserAccount account = UserAccount.register("user", "hash", "昵称", "a@b.com", "13800000000", occurredAt);
    account.assignId(11L);
    account.changeStatus(UserAccountStatus.DISABLED);
    when(mapper.update(any(UserAccountPo.class), any())).thenReturn(1);

    boolean changed = adapter.saveStatusIfVersionMatches(account, 3L, 99L, occurredAt);

    ArgumentCaptor<UserAccountPo> captor = ArgumentCaptor.forClass(UserAccountPo.class);
    verify(mapper).update(captor.capture(), any());
    UserAccountPo po = captor.getValue();
    assertNull(po.getCreatedAt(), "created_* 不参与状态更新");
    assertEquals(99L, po.getUpdatedBy());
    assertEquals(occurredAt, po.getUpdatedAt());
    assertTrue(changed);
  }
}
