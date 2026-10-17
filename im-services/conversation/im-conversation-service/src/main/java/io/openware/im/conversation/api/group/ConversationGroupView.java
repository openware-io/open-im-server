package io.openware.im.conversation.api.group;

import io.openware.common.enums.GroupStatus;
import io.openware.im.conversation.domain.group.model.ConversationGroup;
import io.openware.im.conversation.domain.group.repository.Persisted;
import java.time.LocalDateTime;

/**
 * 群组响应视图：JSON 字段与原领域对象一致，createdAt/updatedAt 从端口返回的记账快照取，
 * 不再把审计列放回领域模型。
 */
public record ConversationGroupView(Long id, String name, String avatar, long ownerId, String announcement,
                                    int maxMembers, boolean allowMemberInvite, boolean allowMemberFriendRequest,
                                    boolean allowMemberViewAccount, GroupStatus status, long authorizationVersion,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {

  public static ConversationGroupView from(Persisted<ConversationGroup> persisted) {
    ConversationGroup group = persisted.aggregate();
    return new ConversationGroupView(group.id(), group.name(), group.avatar(), group.ownerId(),
        group.announcement(), group.maxMembers(), group.allowMemberInvite(), group.allowMemberFriendRequest(),
        group.allowMemberViewAccount(), group.status(), group.authorizationVersion(),
        persisted.audit() == null ? null : persisted.audit().createdAt(),
        persisted.audit() == null ? null : persisted.audit().updatedAt());
  }
}
