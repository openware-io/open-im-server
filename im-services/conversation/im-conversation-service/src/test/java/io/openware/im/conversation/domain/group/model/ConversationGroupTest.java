package io.openware.im.conversation.domain.group.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.enums.GroupStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ConversationGroupTest {
  private final LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);

  @Test
  void ownerAlwaysCanViewMemberAccount() {
    assertTrue(group(false).canViewMemberAccount(1L, 2L));
  }

  @Test
  void memberCanAlwaysViewOwnAccount() {
    assertTrue(group(false).canViewMemberAccount(2L, 2L));
  }

  @Test
  void memberCanViewOthersAccountWhenEnabled() {
    assertTrue(group(true).canViewMemberAccount(2L, 3L));
  }

  @Test
  void memberCannotViewOthersAccountWhenDisabled() {
    assertFalse(group(false).canViewMemberAccount(2L, 3L));
  }

  @Test
  void updateCanToggleViewAccountSetting() {
    ConversationGroup updated = group(true).update(null, null, null, null, null, false, now);
    assertFalse(updated.allowMemberViewAccount());
  }

  private ConversationGroup group(boolean allowMemberViewAccount) {
    return new ConversationGroup(100L, "group", null, 1L, "", 500, true, true, allowMemberViewAccount,
        GroupStatus.ACTIVE, 1, now, now);
  }
}
