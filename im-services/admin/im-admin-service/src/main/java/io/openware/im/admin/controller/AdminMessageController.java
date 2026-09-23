package io.openware.im.admin.controller;

import io.openware.common.dto.AdminListMessagesDto;
import io.openware.common.dto.PageResult;
import io.openware.im.admin.application.query.AdminMessageApplicationService;
import io.openware.im.message.api.admin.AdminMessageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员消息审计接口。
 */
@RestController
@Tag(name = "消息管理")
@RequestMapping("/admin/messages")
@RequiredArgsConstructor
public class AdminMessageController {
  private final AdminMessageApplicationService adminMessageService;

/**
 * 查询列表接口。
 */
  @GetMapping
  public PageResult<AdminMessageResponse> list(@ModelAttribute AdminListMessagesDto dto) {
    return adminMessageService.listMessages(dto);
  }
}

