package com.gvchat.im.message.infra.integration.conversation;

import com.gvchat.im.conversation.api.authorization.ConversationMemberIdsResponse;
import com.gvchat.im.user.api.authorization.UserProfileSummariesQuery;
import com.gvchat.im.user.api.authorization.UserProfileSummary;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.im.message.domain.message.port.MentionResolverPort;
import com.gvchat.im.message.infra.integration.user.UserServiceProperties;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * @ 提及解析适配器：跨 conversation（群成员）与 user（个人昵称/用户名）两个契约解析 @ 目标。
 *
 * <p>解析优先级遵循产品语义「群昵称优先，否则个人昵称」：
 * <ul>
 *   <li>{@code @all}（等价 all / 0）→ mentionsAll 标记，全体成员通知；</li>
 *   <li>纯数字目标 → 视为客户端已解析的用户 id，校验为群成员后透传；</li>
 *   <li>昵称目标 → 个人昵称优先、用户名次之（不区分大小写）匹配为群成员。</li>
 * </ul>
 *
 * <p>注意：conversation 域目前只提供内部 {@code member-ids}（无群昵称），
 * 群昵称优先依赖的「群成员昵称」内部契约尚缺；本实现暂按「个人昵称/用户名」解析，
 * 待 conversation 域新增内部昵称接口后，在 {@link #resolveByProfile} 前接入群昵称匹配即可（见跨服务报告）。
 */
@Component
@Slf4j
public class ConversationUserMentionResolverAdapter implements MentionResolverPort {
  private final RestClient conversationClient;
  private final RestClient userClient;

  public ConversationUserMentionResolverAdapter(RestClient.Builder builder,
      ConversationServiceProperties conversationProperties, UserServiceProperties userProperties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.conversationClient = builder.baseUrl(conversationProperties.getBaseUrl())
        .requestInterceptor(interceptor).build();
    this.userClient = builder.baseUrl(userProperties.getBaseUrl())
        .requestInterceptor(interceptor).build();
  }

  @Override
  public MentionResolution resolveGroupMentions(long groupId, List<String> targets) {
    if (targets == null || targets.isEmpty()) {
      return MentionResolution.empty();
    }
    List<Long> memberIds = findMemberIds(groupId);
    if (memberIds.isEmpty()) {
      return MentionResolution.empty();
    }
    Set<Long> memberSet = Set.copyOf(memberIds);
    Map<Long, UserProfileSummary> profiles = findProfiles(memberIds);

    boolean mentionsAll = false;
    Set<Long> resolved = new LinkedHashSet<>();
    for (String raw : targets) {
      if (raw == null) {
        continue;
      }
      String target = raw.trim();
      if (target.isEmpty()) {
        continue;
      }
      if (isAtAll(target)) {
        mentionsAll = true;
        continue;
      }
      Long numeric = parseId(target);
      if (numeric != null) {
        if (memberSet.contains(numeric)) {
          resolved.add(numeric);
        }
        continue;
      }
      Long matched = resolveByProfile(profiles, memberSet, target);
      if (matched != null) {
        resolved.add(matched);
      }
    }
    return new MentionResolution(mentionsAll, List.copyOf(resolved));
  }

  private boolean isAtAll(String target) {
    return "@all".equalsIgnoreCase(target) || "all".equalsIgnoreCase(target) || "0".equals(target);
  }

  private Long parseId(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private List<Long> findMemberIds(long groupId) {
    ConversationMemberIdsResponse response = conversationClient.get()
        .uri("/internal/conversation/authorizations/{conversationId}/member-ids", groupId)
        .retrieve().body(ConversationMemberIdsResponse.class);
    return response == null || response.userIds() == null ? List.of() : response.userIds();
  }

  private Map<Long, UserProfileSummary> findProfiles(List<Long> memberIds) {
    try {
      UserProfileSummary[] summaries = userClient.post()
          .uri("/internal/user/authorizations/profile-summaries")
          .body(new UserProfileSummariesQuery(memberIds))
          .retrieve().body(UserProfileSummary[].class);
      if (summaries == null) {
        return Map.of();
      }
      return Arrays.stream(summaries)
          .collect(Collectors.toMap(UserProfileSummary::userId, summary -> summary, (left, right) -> left));
    } catch (RuntimeException ex) {
      log.warn("Unable to load user profile summaries for mention resolution, memberCount={}", memberIds.size(), ex);
      return Map.of();
    }
  }

  /** 个人昵称优先、用户名次之（不区分大小写）；群昵称优先待 conversation 内部昵称契约落地后接入。 */
  private Long resolveByProfile(Map<Long, UserProfileSummary> profiles, Set<Long> memberSet, String target) {
    for (Map.Entry<Long, UserProfileSummary> entry : profiles.entrySet()) {
      UserProfileSummary summary = entry.getValue();
      if (memberSet.contains(entry.getKey()) && summary.nickname() != null
          && summary.nickname().equalsIgnoreCase(target)) {
        return entry.getKey();
      }
    }
    for (Map.Entry<Long, UserProfileSummary> entry : profiles.entrySet()) {
      UserProfileSummary summary = entry.getValue();
      if (memberSet.contains(entry.getKey()) && summary.username() != null
          && summary.username().equalsIgnoreCase(target)) {
        return entry.getKey();
      }
    }
    return null;
  }
}
