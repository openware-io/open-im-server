package io.openware.im.conversation.application.secretgroupchat;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.conversation.api.secretgroupchat.CreateSecretGroupChatRequest;
import io.openware.im.conversation.api.secretgroupchat.SecretGroupChatResult;
import io.openware.im.conversation.api.secretgroupchat.SecretGroupChatResult.MemberResult;
import io.openware.im.conversation.domain.secretgroupchat.model.SecretGroupChat;
import io.openware.im.conversation.domain.secretgroupchat.model.SecretGroupMember;
import io.openware.im.conversation.domain.secretchat.repository.DeviceKeyPort;
import io.openware.im.conversation.domain.secretgroupchat.repository.Persisted;
import io.openware.im.conversation.domain.secretgroupchat.repository.SecretGroupChatRepository;
import io.openware.im.conversation.domain.secretgroupchat.repository.SecretGroupMemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 私密群聊应用服务：N 人 E2EE 会话的用例编排 + 参与者鉴权 + 输入校验。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecretGroupChatApplicationService {
  private static final Set<String> DESTROY_POLICIES = Set.of("off", "1s", "2s", "5s", "10s", "30s", "1m", "5m", "1h", "1d", "1w");

  private final SecretGroupChatRepository groupRepository;
  private final SecretGroupMemberRepository memberRepository;
  private final DeviceKeyPort deviceKeyPort;

  @Transactional
  public SecretGroupChatResult createGroup(long ownerId, CreateSecretGroupChatRequest request) {
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    LinkedHashSet<Long> memberIds = new LinkedHashSet<>();
    memberIds.add(ownerId);
    for (Long memberId : request.getMemberUserIds() == null ? List.<Long>of() : request.getMemberUserIds()) {
      if (memberId == null || memberId <= 0) {
        throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid member user id");
      }
      if (memberId == ownerId) {
        throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Cannot add yourself as member");
      }
      memberIds.add(memberId);
    }
    if (memberIds.size() < 2) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Secret group chat requires at least 2 members");
    }
    Persisted<SecretGroupChat> saved = groupRepository.save(SecretGroupChat.create(ownerId));
    // 预填成员设备公钥：全员已在服务端注册设备公钥时，群建立即 ready（Signal 式模型，
    // 无需等待成员逐个提交公钥即可加密发送），并对齐 1 对 1 私密聊天的「创建即发」体验。
    Map<Long, String> keys = deviceKeyPort.findLatestByUserIds(new ArrayList<>(memberIds));
    for (Long memberId : memberIds) {
      SecretGroupMember member = SecretGroupMember.join(saved.aggregate().getId(), memberId, now);
      String key = keys.get(memberId);
      if (key != null && !key.isBlank()) {
        member = member.submitPublicKey(key);
      }
      memberRepository.save(member);
    }
    // 全员公钥齐备则计算群安全码，使群一建立即 ready。
    List<SecretGroupMember> members = memberRepository.findByGroupId(saved.aggregate().getId());
    if (members.stream().allMatch(SecretGroupMember::hasPublicKey)) {
      String safeCode = SecretGroupChat.computeSafeCode(
          members.stream().map(SecretGroupMember::getDevicePublicKey).toList());
      saved = groupRepository.save(saved.aggregate().withSafeCode(safeCode));
    }
    log.info("Secret group chat created, groupId={}, ownerId={}, memberCount={}", saved.aggregate().getId(), ownerId, memberIds.size());
    return toResult(saved);
  }

  @Transactional
  public SecretGroupChatResult addMember(long operatorId, long groupId, long userId) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    if (!group.aggregate().isActive()) {
      throw new ApiException(HttpStatusCodes.GONE, "Secret group chat is closed");
    }
    if (userId <= 0) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid member user id");
    }
    if (memberRepository.findByGroupIdAndUserId(groupId, userId).isPresent()) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "User is already a member");
    }
    memberRepository.save(SecretGroupMember.join(groupId, userId, LocalDateTime.now(Clock.systemUTC())));
    return toResult(group);
  }

  @Transactional
  public SecretGroupChatResult submitPublicKey(long userId, long groupId, String publicKey) {
    Persisted<SecretGroupChat> group = requireMember(userId, groupId);
    SecretGroupMember member = memberRepository.findByGroupIdAndUserId(groupId, userId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Member not found"));
    memberRepository.save(member.submitPublicKey(publicKey));
    // 全体成员公钥齐备后计算群安全码（排序后公钥串 SHA-256）。
    List<SecretGroupMember> members = memberRepository.findByGroupId(groupId);
    if (members.stream().allMatch(SecretGroupMember::hasPublicKey)) {
      String safeCode = SecretGroupChat.computeSafeCode(
          members.stream().map(SecretGroupMember::getDevicePublicKey).toList());
      group = groupRepository.save(group.aggregate().withSafeCode(safeCode));
    }
    log.info("Secret group chat handshake, groupId={}, userId={}", groupId, userId);
    return toResult(group);
  }

  @Transactional(readOnly = true)
  public SecretGroupChatResult getGroup(long userId, long groupId) {
    return toResult(requireMember(userId, groupId));
  }

  @Transactional(readOnly = true)
  public List<SecretGroupChatResult> listMyGroups(long userId) {
    List<Long> groupIds = memberRepository.findByUserId(userId).stream()
        .map(SecretGroupMember::getSecretGroupId).distinct().toList();
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return groupRepository.findByIds(groupIds).stream().map(this::toResult).toList();
  }

  @Transactional
  public SecretGroupChatResult setDestroyPolicy(long operatorId, long groupId, String policy) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    if (policy == null || !DESTROY_POLICIES.contains(policy)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid destroy policy");
    }
    Persisted<SecretGroupChat> saved = groupRepository.save(group.aggregate().withDestroyPolicy(policy));
    return toResult(saved);
  }

  @Transactional
  public SecretGroupChatResult setAnonymousEnabled(long operatorId, long groupId, boolean enabled) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    Persisted<SecretGroupChat> saved = groupRepository.save(group.aggregate().withAnonymousEnabled(enabled));
    return toResult(saved);
  }

  @Transactional
  public SecretGroupChatResult pinMessage(long operatorId, long groupId, String msgId) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    if (msgId == null || msgId.isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "msgId is required");
    }
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    Persisted<SecretGroupChat> saved = groupRepository.save(group.aggregate().withPinned(msgId, now));
    return toResult(saved);
  }

  @Transactional
  public SecretGroupChatResult unpinMessage(long operatorId, long groupId) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    Persisted<SecretGroupChat> saved = groupRepository.save(group.aggregate().withPinned(null, null));
    return toResult(saved);
  }

  @Transactional
  public SecretGroupChatResult generateInvite(long operatorId, long groupId) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    String token = java.util.UUID.randomUUID().toString().replace("-", "");
    LocalDateTime expiresAt = LocalDateTime.now(Clock.systemUTC()).plusDays(7);
    Persisted<SecretGroupChat> saved = groupRepository.save(group.aggregate().withInvite(token, expiresAt));
    return toResult(saved);
  }

  @Transactional
  public SecretGroupChatResult joinByInvite(long userId, String token) {
    Persisted<SecretGroupChat> row = groupRepository.findByInviteToken(token)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Invite link invalid or expired"));
    SecretGroupChat group = row.aggregate();
    if (!group.isActive()) {
      throw new ApiException(HttpStatusCodes.GONE, "Secret group chat is closed");
    }
    if (group.getInviteExpiresAt() != null && group.getInviteExpiresAt().isBefore(LocalDateTime.now(Clock.systemUTC()))) {
      throw new ApiException(HttpStatusCodes.GONE, "Invite link expired");
    }
    if (memberRepository.findByGroupIdAndUserId(group.getId(), userId).isPresent()) {
      return toResult(row); // 已是成员，幂等
    }
    memberRepository.save(SecretGroupMember.join(group.getId(), userId, LocalDateTime.now(Clock.systemUTC())));
    return toResult(row);
  }

  @Transactional
  public SecretGroupChatResult setOwnerOnlyPost(long operatorId, long groupId, boolean enabled) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    Persisted<SecretGroupChat> saved = groupRepository.save(group.aggregate().withOwnerOnlyPost(enabled));
    return toResult(saved);
  }

  @Transactional
  public SecretGroupChatResult renameGroup(long operatorId, long groupId, String name) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.length() > 128) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Group name too long");
    }
    Persisted<SecretGroupChat> saved = groupRepository.save(group.aggregate().withName(trimmed.isEmpty() ? null : trimmed));
    return toResult(saved);
  }

  @Transactional
  public SecretGroupChatResult setAnnouncement(long operatorId, long groupId, String announcement) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    String trimmed = announcement == null ? "" : announcement.trim();
    if (trimmed.length() > 2000) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Announcement too long");
    }
    Persisted<SecretGroupChat> saved = groupRepository.save(group.aggregate().withAnnouncement(trimmed.isEmpty() ? null : trimmed));
    return toResult(saved);
  }

  @Transactional
  public SecretGroupChatResult removeMember(long operatorId, long groupId, long userId) {
    requireOwner(operatorId, groupId);
    if (userId <= 0 || userId == operatorId) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid member user id");
    }
    if (memberRepository.findByGroupIdAndUserId(groupId, userId).isEmpty()) {
      throw new ApiException(HttpStatusCodes.NOT_FOUND, "Member not found");
    }
    memberRepository.deleteByGroupIdAndUserId(groupId, userId);
    log.info("Secret group chat member removed, groupId={}, userId={}, operatorId={}", groupId, userId, operatorId);
    return toResult(requireGroup(groupId));
  }

  /** 内部接口：返回会话是否「仅群主可发言」；不存在按 false。 */
  @Transactional(readOnly = true)
  public boolean ownerOnlyPostOf(long groupId) {
    return groupRepository.findById(groupId).map(row -> row.aggregate().isOwnerOnlyPost()).orElse(false);
  }

  /** 内部接口：返回群主用户标识；不存在返回 0。 */
  @Transactional(readOnly = true)
  public long ownerOf(long groupId) {
    return groupRepository.findById(groupId).map(row -> row.aggregate().getOwnerUserId()).orElse(0L);
  }

  @Transactional
  public void leaveGroup(long userId, long groupId) {
    requireMember(userId, groupId);
    memberRepository.deleteByGroupIdAndUserId(groupId, userId);
    log.info("Secret group chat member left, groupId={}, userId={}", groupId, userId);
  }

  @Transactional
  public void deleteGroup(long operatorId, long groupId) {
    Persisted<SecretGroupChat> group = requireOwner(operatorId, groupId);
    memberRepository.deleteByGroupId(groupId);
    groupRepository.deleteById(group.aggregate().getId());
    log.info("Secret group chat deleted, groupId={}, userId={}", groupId, operatorId);
  }

  /** 内部接口：返回会话当前销毁策略；不存在时按 off 处理。 */
  @Transactional(readOnly = true)
  public String destroyPolicyOf(long groupId) {
    return groupRepository.findById(groupId).map(row -> row.aggregate().getDestroyPolicy()).orElse("off");
  }

  /** 内部接口：返回会话全部成员用户标识（供消息服务计算按成员密文与推送接收方）。 */
  @Transactional(readOnly = true)
  public List<Long> participantsOf(long groupId) {
    requireGroup(groupId);
    return memberRepository.findByGroupId(groupId).stream().map(SecretGroupMember::getUserId).toList();
  }

  private Persisted<SecretGroupChat> requireGroup(long groupId) {
    return groupRepository.findById(groupId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Secret group chat not found"));
  }

  private Persisted<SecretGroupChat> requireMember(long userId, long groupId) {
    Persisted<SecretGroupChat> row = requireGroup(groupId);
    if (memberRepository.findByGroupIdAndUserId(groupId, userId).isEmpty()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not a member");
    }
    return row;
  }

  private Persisted<SecretGroupChat> requireOwner(long userId, long groupId) {
    Persisted<SecretGroupChat> row = requireGroup(groupId);
    if (!row.aggregate().isOwner(userId)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only owner can perform this action");
    }
    return row;
  }

  private SecretGroupChatResult toResult(Persisted<SecretGroupChat> row) {
    SecretGroupChat group = row.aggregate();
    List<MemberResult> members = memberRepository.findByGroupId(group.getId()).stream()
        .map(member -> new MemberResult(member.getUserId(), member.getDevicePublicKey(), member.getJoinedAt()))
        .toList();
    return new SecretGroupChatResult(group.getId(), group.getOwnerUserId(), group.getStatus(), group.getName(),
        group.getAnnouncement(), group.getSafeCode(), group.getDestroyPolicy(), group.isAnonymousEnabled(),
        group.getPinnedMsgId(), group.getPinnedAt(), group.getInviteToken(), group.getInviteExpiresAt(),
        group.isOwnerOnlyPost(), members, row.audit().createdBy(), row.audit().createdAt(),
        row.audit().updatedBy(), row.audit().updatedAt());
  }
}
