package io.openware.group.idaas.api.controller;

import io.openware.group.idaas.api.dto.ClientDtos.CreateClientRequest;
import io.openware.group.idaas.api.dto.ClientDtos.IdaasClientDto;
import io.openware.group.idaas.application.ClientApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 授权域接口：接入方（OAuth client）管理（服务内路径 /clients/**，无 /api 前缀）。
 */
@RestController
@RequestMapping("/clients")
public class ClientController {

  private final ClientApplicationService clientApplicationService;

  public ClientController(ClientApplicationService clientApplicationService) {
    this.clientApplicationService = clientApplicationService;
  }

  @GetMapping
  public List<IdaasClientDto> list() {
    return clientApplicationService.list();
  }

  @PostMapping
  public IdaasClientDto create(@Valid @RequestBody CreateClientRequest request) {
    return clientApplicationService.create(request);
  }
}
