package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.infra.persistence.mapper.AppGrantMapper;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户应用授权内部查询：服务入口按 account 过滤。 */
@RestController
@RequestMapping("/internal/iam")
public class InternalAppGrantController {

  private final AppGrantMapper mapper;

  public InternalAppGrantController(AppGrantMapper mapper) {
    this.mapper = mapper;
  }

  @GetMapping("/accounts/{accountId}/app-grants")
  public List<String> grants(@PathVariable Long accountId, @RequestParam Long tenantId) {
    return mapper.selectAuthorizedApps(accountId, tenantId);
  }
}
