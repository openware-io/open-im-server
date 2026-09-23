package io.openware.im.user.infra.persistence.openplatform.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.openplatform.model.OpenApplication;
import io.openware.im.user.domain.openplatform.model.OpenApplicationStatus;
import io.openware.im.user.domain.openplatform.repository.OpenApplicationRepository;
import io.openware.im.user.infra.persistence.openplatform.converter.OpenPlatformPersistenceConverter;
import io.openware.im.user.infra.persistence.openplatform.mapper.OpenApplicationMapper;
import io.openware.im.user.infra.persistence.openplatform.mapper.OpenApplicationScopeMapper;
import io.openware.im.user.infra.persistence.openplatform.mapper.UserOidcRedirectUriMapper;
import io.openware.im.user.infra.persistence.openplatform.po.OpenApplicationPo;
import io.openware.im.user.infra.persistence.openplatform.po.OpenApplicationScopePo;
import io.openware.im.user.infra.persistence.openplatform.po.UserOidcRedirectUriPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OpenApplicationRepositoryAdapter implements OpenApplicationRepository {
  private final OpenApplicationMapper applicationMapper;
  private final OpenApplicationScopeMapper scopeMapper;
  private final UserOidcRedirectUriMapper redirectUriMapper;

  @Override
  public Optional<OpenApplication> findByAppId(String appId) {
    OpenApplicationPo po = applicationMapper.selectOne(Wrappers.<OpenApplicationPo>lambdaQuery()
        .eq(OpenApplicationPo::getAppId, appId));
    if (po == null) {
      return Optional.empty();
    }
    return Optional.of(OpenPlatformPersistenceConverter.toDomain(po, findScopes(po.getId())));
  }

  @Override
  public boolean isRedirectUriRegistered(String appId, String redirectUri) {
    return redirectUriMapper.selectCount(Wrappers.<UserOidcRedirectUriPo>lambdaQuery()
        .eq(UserOidcRedirectUriPo::getClientId, appId)
        .eq(UserOidcRedirectUriPo::getRedirectUri, redirectUri)) > 0;
  }

  @Override
  public OpenApplication save(OpenApplication application) {
    OpenApplicationPo po = OpenPlatformPersistenceConverter.toPo(application);
    if (po.getId() == null) {
      applicationMapper.insert(po);
      application.assignId(po.getId());
      for (String scope : application.getScopes()) {
        scopeMapper.insert(OpenPlatformPersistenceConverter.toScopePo(po.getId(), scope));
      }
    } else {
      applicationMapper.updateById(po);
      syncScopes(po.getId(), application.getScopes());
    }
    return application;
  }

  @Override
  public PageResult<OpenApplication> list(OpenApplicationStatus status, String appType, int page, int pageSize) {
    var query = Wrappers.<OpenApplicationPo>lambdaQuery()
        .eq(status != null, OpenApplicationPo::getStatus, status == null ? null : status.name())
        .eq(appType != null && !appType.isBlank(), OpenApplicationPo::getAppType, appType)
        .orderByAsc(OpenApplicationPo::getSubjectName)
        .orderByDesc(OpenApplicationPo::getId);
    Page<OpenApplicationPo> result = applicationMapper.selectPage(new Page<>(page, pageSize), query);
    List<OpenApplication> items = result.getRecords().stream()
        .map(po -> OpenPlatformPersistenceConverter.toDomain(po, findScopes(po.getId()))).toList();
    return PageResult.<OpenApplication>builder().items(items).total(result.getTotal())
        .page(page).pageSize(pageSize).build();
  }

  private void syncScopes(Long applicationId, List<String> scopes) {
    scopeMapper.delete(Wrappers.<OpenApplicationScopePo>lambdaQuery()
        .eq(OpenApplicationScopePo::getApplicationId, applicationId));
    for (String scope : scopes) {
      scopeMapper.insert(OpenPlatformPersistenceConverter.toScopePo(applicationId, scope));
    }
  }

  private List<String> findScopes(Long applicationId) {
    return scopeMapper.selectList(Wrappers.<OpenApplicationScopePo>lambdaQuery()
            .eq(OpenApplicationScopePo::getApplicationId, applicationId)).stream()
        .map(OpenApplicationScopePo::getScope).toList();
  }
}
