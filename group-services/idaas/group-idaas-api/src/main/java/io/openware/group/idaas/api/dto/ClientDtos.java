package io.openware.group.idaas.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 授权域 DTO：接入方（OAuth client）管理。
 */
public final class ClientDtos {
  private ClientDtos() {}

  /** 接入方视图。 */
  public record IdaasClientDto(Long id, String name, String clientId, String status, List<String> redirectUris) {}

  /** 创建接入方请求。 */
  public record CreateClientRequest(
      @NotBlank(message = "接入方名称不能为空") String name,
      @NotBlank(message = "clientId 不能为空") String clientId,
      @NotBlank(message = "clientSecret 不能为空") String clientSecret,
      List<String> redirectUris) {}
}
