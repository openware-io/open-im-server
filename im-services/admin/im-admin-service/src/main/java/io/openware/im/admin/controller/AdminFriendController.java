package io.openware.im.admin.controller;

import io.openware.common.dto.AdminListFriendsDto;
import io.openware.common.dto.PageResult;
import io.openware.im.admin.application.query.AdminFriendApplicationService;
import io.openware.im.user.api.admin.AdminFriendResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 */
@RestController
@RequestMapping("/admin/friends")
@RequiredArgsConstructor
public class AdminFriendController {
  private final AdminFriendApplicationService adminFriendService;

/**
 */
  @GetMapping
  public PageResult<AdminFriendResponse> list(@ModelAttribute AdminListFriendsDto dto) {
    return adminFriendService.listFriends(dto);
  }
}

