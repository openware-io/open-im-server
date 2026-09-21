package com.gvchat.group.idaas.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.group.idaas.api.dto.ClientDtos.CreateClientRequest;
import com.gvchat.group.idaas.api.dto.ClientDtos.IdaasClientDto;
import com.gvchat.group.idaas.infra.persistence.mapper.IdaasClientMapper;
import com.gvchat.group.idaas.infra.persistence.po.IdaasClientPo;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 授权域：接入方（OAuth client）注册与查询，client_id 唯一键提供幂等。
 */
@Service
public class ClientApplicationService {

  private static final String CLIENT_ENABLED = "ENABLED";

  private final IdaasClientMapper clientMapper;

  public ClientApplicationService(IdaasClientMapper clientMapper) {
    this.clientMapper = clientMapper;
  }

  /** 列出全部接入方。 */
  public List<IdaasClientDto> list() {
    return clientMapper.selectList(new QueryWrapper<IdaasClientPo>().orderByAsc("id"))
        .stream().map(this::toDto).toList();
  }

  /** 创建接入方：client_id 已存在时幂等返回已有接入方。 */
  public IdaasClientDto create(CreateClientRequest request) {
    IdaasClientPo existing = clientMapper.selectOne(
        new QueryWrapper<IdaasClientPo>().eq("client_id", request.clientId()));
    if (existing != null) {
      return toDto(existing);
    }
    IdaasClientPo po = new IdaasClientPo();
    po.setName(request.name());
    po.setClientId(request.clientId());
    po.setClientSecret(request.clientSecret());
    po.setRedirectUris(join(request.redirectUris()));
    po.setStatus(CLIENT_ENABLED);
    po.setCreatedAt(LocalDateTime.now());
    po.setUpdatedAt(LocalDateTime.now());
    clientMapper.insert(po);
    return toDto(po);
  }

  private IdaasClientDto toDto(IdaasClientPo po) {
    return new IdaasClientDto(po.getId(), po.getName(), po.getClientId(), po.getStatus(),
        split(po.getRedirectUris()));
  }

  private static String join(List<String> uris) {
    return uris == null ? null : String.join(",", uris);
  }

  private static List<String> split(String uris) {
    return uris == null || uris.isBlank() ? List.of() : Arrays.asList(uris.split(","));
  }
}
