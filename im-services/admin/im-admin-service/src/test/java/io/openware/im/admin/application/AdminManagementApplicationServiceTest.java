package io.openware.im.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.im.admin.domain.miniapp.MiniappServiceItem;
import io.openware.im.admin.domain.miniapp.MiniappServiceType;
import io.openware.im.admin.domain.repository.AdminConfigurationRepository;
import io.openware.im.admin.domain.repository.MiniappRepository;
import io.openware.im.admin.domain.repository.ModerationRepository;
import io.openware.im.admin.integration.AdminReadClient;
import io.openware.im.admin.media.MediaReferenceClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminManagementApplicationServiceTest {
  private MiniappRepository miniappRepository;
  private AdminManagementApplicationService service;

  @BeforeEach
  void setUp() {
    miniappRepository = mock(MiniappRepository.class);
    service = new AdminManagementApplicationService(
        mock(AdminConfigurationRepository.class),
        mock(ModerationRepository.class),
        miniappRepository,
        mock(MediaReferenceClient.class),
        mock(AdminReadClient.class));
  }

  private static MiniappServiceItem item(Boolean status) {
    return new MiniappServiceItem(1, 1, "外卖", "https://x", "", null, status, false, MiniappServiceItem.AUDIENCE_CONSUMER, false, 0,
        LocalDateTime.now(), LocalDateTime.now());
  }

  private static MiniappServiceItem hiddenItem(Boolean hidden) {
    return new MiniappServiceItem(1, 1, "A380后台", "https://x/b/", "", null, true, false, MiniappServiceItem.AUDIENCE_OPERATOR, hidden, 0,
        LocalDateTime.now(), LocalDateTime.now());
  }

  @Test
  void toggleFlipsEnabledToDisabled() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(item(true)));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));
    assertEquals(false, service.toggleItem(1).status());
  }

  @Test
  void publishedConsumerItemsExcludesOperatorEntries() {
    List<MiniappServiceItem> consumerItems = List.of(item(true));
    when(miniappRepository.findPublishedConsumerItems()).thenReturn(consumerItems);

    assertEquals(consumerItems, service.publishedConsumerItems());
    verify(miniappRepository, never()).findPublishedItems();
  }

  @Test
  void toggleFlipsDisabledToEnabled() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(item(false)));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));
    assertEquals(true, service.toggleItem(1).status());
  }

  @Test
  void setStatusDisablesEnabledItem() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(item(true)));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));
    assertEquals(false, service.setItemStatus(1, 0).status());
  }

  @Test
  void setStatusEnablesDisabledItem() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(item(false)));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));
    assertEquals(true, service.setItemStatus(1, 1).status());
  }

  @Test
  void setStatusIsIdempotentWhenAlreadyDisabled() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(item(false)));
    assertEquals(false, service.setItemStatus(1, 0).status());
    verify(miniappRepository, never()).saveItem(any());
  }

  @Test
  void setStatusRejectsInvalidValue() {
    assertThrows(ApiException.class, () -> service.setItemStatus(1, 5));
    assertThrows(ApiException.class, () -> service.setItemStatus(1, null));
  }

  @Test
  void setStatusThrowsNotFoundForMissingItem() {
    when(miniappRepository.findItemById(99)).thenReturn(Optional.empty());
    assertThrows(ApiException.class, () -> service.setItemStatus(99, 1));
  }

  // ─── 隐藏属性：隐藏只影响「列表展示」，不得影响启停、排序等既有行为 ───

  @Test
  void setItemHiddenHidesVisibleItem() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(hiddenItem(false)));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));

    MiniappServiceItem saved = service.setItemHidden(1, 1);

    assertEquals(true, saved.hidden());
    assertEquals(true, saved.status(), "隐藏不得顺手改动启用状态");
  }

  @Test
  void setItemHiddenIsIdempotentWhenAlreadyHidden() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(hiddenItem(true)));
    assertEquals(true, service.setItemHidden(1, 1).hidden());
    verify(miniappRepository, never()).saveItem(any());
  }

  @Test
  void setItemHiddenCanUnhide() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(hiddenItem(true)));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));
    assertEquals(false, service.setItemHidden(1, 0).hidden());
  }

  @Test
  void setItemHiddenRejectsInvalidValue() {
    assertThrows(ApiException.class, () -> service.setItemHidden(1, 5));
    assertThrows(ApiException.class, () -> service.setItemHidden(1, null));
  }

  @Test
  void toggleStatusPreservesHiddenFlag() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(hiddenItem(true)));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));

    MiniappServiceItem saved = service.toggleItem(1);

    assertEquals(true, saved.hidden(), "启停切换不得把隐藏项变回可见");
    assertEquals(false, saved.status());
  }

  @Test
  void updateItemWithoutHiddenFieldKeepsCurrentHiddenFlag() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(hiddenItem(true)));
    when(miniappRepository.findTypeById(1)).thenReturn(Optional.of(
        new MiniappServiceType(1, "运营后台", 0, true, LocalDateTime.now(), LocalDateTime.now())));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));

    // 旧客户端不带 hidden 字段时，不能把已有隐藏项改回可见。
    MiniappServiceItem saved = service.updateItem(0L, 1,
        new AdminManagementApplicationService.ItemPatch(null, "改名", null, null, null, null, null, null, null, null));

    assertEquals(true, saved.hidden());
    assertEquals("改名", saved.name());
  }

  @Test
  void updateItemCanExplicitlyUnhide() {
    when(miniappRepository.findItemById(1)).thenReturn(Optional.of(hiddenItem(true)));
    when(miniappRepository.findTypeById(1)).thenReturn(Optional.of(
        new MiniappServiceType(1, "运营后台", 0, true, LocalDateTime.now(), LocalDateTime.now())));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));

    MiniappServiceItem saved = service.updateItem(0L, 1,
        new AdminManagementApplicationService.ItemPatch(null, null, null, null, null, null, null, null, 0, null));

    assertEquals(false, saved.hidden());
  }

  @Test
  void createItemIsVisibleByDefault() {
    when(miniappRepository.findTypeById(1)).thenReturn(Optional.of(
        new MiniappServiceType(1, "小程序", 0, false, LocalDateTime.now(), LocalDateTime.now())));
    when(miniappRepository.saveItem(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceItem.class));

    MiniappServiceItem saved = service.createItem(0L,
        new AdminManagementApplicationService.ItemChange(1, "外卖", "https://x", null, null, null, null, null, null, null));

    assertEquals(false, saved.hidden(), "不传 hidden 时保持既有默认：展示");
  }

  @Test
  void createTypeIsVisibleByDefaultAndCanBeHidden() {
    when(miniappRepository.saveType(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceType.class));

    assertEquals(false, service.createType("小程序", 0, null).hidden());
    assertEquals(true, service.createType("运营后台", 0, 1).hidden());
  }

  @Test
  void updateTypeWithoutHiddenFieldKeepsCurrentHiddenFlag() {
    when(miniappRepository.findTypeById(4)).thenReturn(Optional.of(
        new MiniappServiceType(4, "运营后台", 0, true, LocalDateTime.now(), LocalDateTime.now())));
    when(miniappRepository.saveType(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceType.class));

    assertEquals(true, service.updateType(4, null, null, null).hidden());
    assertEquals(false, service.updateType(4, null, null, 0).hidden());
  }

  @Test
  void sortTypesPreservesHiddenFlag() {
    when(miniappRepository.findTypeById(4)).thenReturn(Optional.of(
        new MiniappServiceType(4, "运营后台", 0, true, LocalDateTime.now(), LocalDateTime.now())));
    when(miniappRepository.saveType(any())).thenAnswer(inv -> inv.getArgument(0, MiniappServiceType.class));

    service.sortTypes(java.util.List.of(new AdminManagementApplicationService.TypeSort(4, 9)));

    var captor = org.mockito.ArgumentCaptor.forClass(MiniappServiceType.class);
    verify(miniappRepository).saveType(captor.capture());
    assertEquals(9, captor.getValue().sortOrder());
    assertEquals(true, captor.getValue().hidden(), "排序不得把隐藏分组变回可见");
  }
}
