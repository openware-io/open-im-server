package io.openware.group.idaas.application;

import io.openware.group.idaas.api.dto.ClientDtos.CreateClientRequest;
import io.openware.group.idaas.api.dto.ClientDtos.IdaasClientDto;
import io.openware.group.idaas.domain.client.model.IdaasClient;
import io.openware.group.idaas.domain.client.repository.IdaasClientRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 授权域：接入方（OAuth client）注册与查询，client_id 唯一键提供幂等。
 *
 * <p>持久化经领域端口；新建接入方走领域工厂（状态固定为 ENABLED）。
 */
@Service
public class ClientApplicationService {

  private final IdaasClientRepository clientRepository;

  public ClientApplicationService(IdaasClientRepository clientRepository) {
    this.clientRepository = clientRepository;
  }

  /** 列出全部接入方。 */
  public List<IdaasClientDto> list() {
    return clientRepository.listAll().stream().map(this::toDto).toList();
  }

  /** 创建接入方：client_id 已存在时幂等返回已有接入方。 */
  public IdaasClientDto create(CreateClientRequest request) {
    IdaasClient existing = clientRepository.findByClientId(request.clientId()).orElse(null);
    if (existing != null) {
      return toDto(existing);
    }
    IdaasClient created = clientRepository.save(IdaasClient.register(
        request.name(), request.clientId(), request.clientSecret(), join(request.redirectUris())));
    return toDto(created);
  }

  private IdaasClientDto toDto(IdaasClient client) {
    return new IdaasClientDto(client.id(), client.name(), client.clientId(), client.status(),
        split(client.redirectUris()));
  }

  private static String join(List<String> uris) {
    return uris == null ? null : String.join(",", uris);
  }

  private static List<String> split(String uris) {
    return uris == null || uris.isBlank() ? List.of() : Arrays.asList(uris.split(","));
  }
}
