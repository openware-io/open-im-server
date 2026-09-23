package io.openware.im.conversation.controller;

import java.util.List;
import io.openware.im.conversation.api.group.AddGroupMembersRequest;
import io.openware.im.conversation.api.group.CreateGroupRequest;
import io.openware.im.conversation.api.group.MuteGroupMemberRequest;
import io.openware.im.conversation.api.group.SetGroupMemberRoleRequest;
import io.openware.im.conversation.api.group.UpdateGroupRequest;
import io.openware.im.conversation.api.group.GroupMemberResponse;
import io.openware.im.conversation.application.group.GroupApplicationService;
import io.openware.im.conversation.domain.group.model.ConversationGroup;
import io.openware.im.conversation.domain.group.model.ConversationMember;
import io.openware.infrastructure.security.SecurityUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 */
@RestController
@Tag(name = "群组管理")
@RequestMapping("/groups")
@RequiredArgsConstructor
public class GroupController {
private final GroupApplicationService groupService;

  /**
   * 创建群组。
   *
   * @param user 当前登录用户
   * @param dto 创建群组请求
   */
  @PostMapping
  public ConversationGroup createGroup(@AuthenticationPrincipal SecurityUser user, @Valid @RequestBody CreateGroupRequest dto) {
    return groupService.createGroup(user.getId(), dto);
  }

  /**
   * 查询当前用户加入的群组。
   *
   * @param user 当前登录用户
   */
  @GetMapping("/mine")
  public List<ConversationGroup> getMyGroups(@AuthenticationPrincipal SecurityUser user) {
    return groupService.getUserGroups(user.getId());
  }

  /**
   * 查询群组详情。
   */
  @GetMapping("/{id}")
  public ConversationGroup getGroupInfo(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    return groupService.getGroupInfo(id, user.getId());
  }

  /**
   * 查询群成员列表。
   */
  @GetMapping("/{id}/members")
  public List<GroupMemberResponse> getMembers(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    return groupService.getGroupMembers(id, user.getId());
  }

  /**
   *
   * @param user 当前登录用户
   * @param dto 待更新字段
   */
  @PutMapping("/{id}")
  public ConversationGroup updateGroup(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id, @Valid @RequestBody UpdateGroupRequest dto) {
    return groupService.updateGroup(id, user.getId(), dto);
  }

  /**
   *
   * @param dto 待添加的用户 ID 列表
   */
  @PostMapping("/{id}/members")
  public Map<String, Integer> addMembers(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id, @Valid @RequestBody AddGroupMembersRequest dto) {
    return groupService.addMembers(id, user.getId(), dto);
  }

  /**
   * 移除指定群成员。
   *
   * @param userId 被移除的成员 ID
   */
  @DeleteMapping("/{id}/members/{userId}")
  public void removeMember(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id, @PathVariable Long userId) {
    groupService.removeMember(id, user.getId(), userId);
  }

  /**
   * 退出群组。
   *
   * @param user 当前登录用户
   */
  @PostMapping("/{id}/leave")
  public void leaveGroup(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    groupService.leaveGroup(id, user.getId());
  }

  /**
   * 设置当前用户在群内的昵称。
   */
  @PutMapping("/{id}/members/me/nickname")
  public ConversationMember updateMyNickname(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @RequestBody NicknameRequest dto) {
    return groupService.updateMyNickname(id, user.getId(), dto == null ? null : dto.nickname());
  }

  public record NicknameRequest(String nickname) {}

  /**
   * 对群成员设置或取消禁言。
   *
   * @param dto 目标成员及禁言时长
   * @return 更新后的成员记录
   */
  @PostMapping("/{id}/mute")
  public ConversationMember muteMember(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id, @Valid @RequestBody MuteGroupMemberRequest dto) {
    return groupService.muteMember(id, user.getId(), dto);
  }

  /**
   * 更新群成员角色。
   * @return 更新后的成员记录
   */
  @PostMapping("/{id}/role")
  public ConversationMember setRole(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id, @Valid @RequestBody SetGroupMemberRoleRequest dto) {
    return groupService.setRole(id, user.getId(), dto);
  }

  /**
   * 解散群组。
   *
   * @param user 当前登录用户
   */
  @DeleteMapping("/{id}")
  public void dissolveGroup(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    groupService.dissolveGroup(id, user.getId());
  }
}

