package com.gvchat.im.admin.controller;

import com.gvchat.im.admin.application.query.AdminCrossDomainQueryService;
import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.api.admin.AdminUserStickerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 */
@RestController
@RequestMapping("/admin/user-stickers")
@RequiredArgsConstructor
public class AdminUserStickerController {
  private final AdminCrossDomainQueryService queryService;

/**
 */
  @GetMapping
  public PageResult<AdminUserStickerResponse> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) Long userId) {
    return queryService.listStickers(page, pageSize, userId);
  }

/**
 */
  @DeleteMapping("/{id}")
  public void remove(@PathVariable Long id) {
    queryService.deleteSticker(id);
  }
}

