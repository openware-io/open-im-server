package com.gvchat.im.admin.controller;

import com.gvchat.common.dto.AdminListFriendsDto;
import com.gvchat.common.dto.PageResult;
import com.gvchat.im.admin.application.query.AdminFriendApplicationService;
import com.gvchat.im.user.api.admin.AdminFriendResponse;
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

