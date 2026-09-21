package com.gvchat.im.admin.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gvchat.im.admin.domain.configuration.AdminConfiguration;
import com.gvchat.im.admin.domain.miniapp.MiniappServiceItem;
import com.gvchat.im.admin.domain.miniapp.MiniappServiceType;
import com.gvchat.im.admin.domain.moderation.Report;
import com.gvchat.im.admin.domain.moderation.SensitiveWord;
import com.gvchat.im.admin.domain.moderation.Violation;
import com.gvchat.im.admin.domain.repository.AdminConfigurationRepository;
import com.gvchat.im.admin.domain.repository.MiniappRepository;
import com.gvchat.im.admin.domain.repository.ModerationRepository;
import com.gvchat.im.admin.infra.persistence.mapper.AdminConfigurationMapper;
import com.gvchat.im.admin.infra.persistence.mapper.MiniappServiceItemMapper;
import com.gvchat.im.admin.infra.persistence.mapper.MiniappServiceTypeMapper;
import com.gvchat.im.admin.infra.persistence.mapper.ReportMapper;
import com.gvchat.im.admin.infra.persistence.mapper.SensitiveWordMapper;
import com.gvchat.im.admin.infra.persistence.mapper.ViolationMapper;
import com.gvchat.im.admin.infra.persistence.po.AdminConfigurationPo;
import com.gvchat.im.admin.infra.persistence.po.MiniappServiceItemPo;
import com.gvchat.im.admin.infra.persistence.po.MiniappServiceTypePo;
import com.gvchat.im.admin.infra.persistence.po.ReportPo;
import com.gvchat.im.admin.infra.persistence.po.SensitiveWordPo;
import com.gvchat.im.admin.infra.persistence.po.ViolationPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminManagementPersistenceAdapter implements AdminConfigurationRepository, MiniappRepository,
    ModerationRepository {
  private final AdminConfigurationMapper configurationMapper;
  private final ReportMapper reportMapper;
  private final ViolationMapper violationMapper;
  private final SensitiveWordMapper sensitiveWordMapper;
  private final MiniappServiceTypeMapper typeMapper;
  private final MiniappServiceItemMapper itemMapper;

  @Override public List<AdminConfiguration> findAll() { return configurationMapper.selectList(null).stream().map(this::configuration).toList(); }
  @Override public Optional<AdminConfiguration> findByKey(String key) { return Optional.ofNullable(configurationMapper.selectOne(Wrappers.<AdminConfigurationPo>lambdaQuery().eq(AdminConfigurationPo::getConfigKey, key))).map(this::configuration); }
  @Override public AdminConfiguration save(AdminConfiguration value) { AdminConfigurationPo po = configurationPo(value); if (po.getId() == null) configurationMapper.insert(po); else configurationMapper.updateById(po); return configuration(po); }
  @Override public Optional<Report> findReportById(Long id) { return Optional.ofNullable(reportMapper.selectById(id)).map(this::report); }
  @Override public boolean hasPendingReport(Long reporterId, Long targetId) { return reportMapper.selectCount(Wrappers.<ReportPo>lambdaQuery().eq(ReportPo::getReporterId, reporterId).eq(ReportPo::getTargetId, targetId).eq(ReportPo::getStatus, com.gvchat.common.enums.ReportStatus.PENDING)) > 0; }
  @Override public long countPendingReports() { return reportMapper.selectCount(Wrappers.<ReportPo>lambdaQuery().eq(ReportPo::getStatus, com.gvchat.common.enums.ReportStatus.PENDING)); }
  @Override public List<Report> findReports(com.gvchat.common.enums.ReportStatus status, int offset, int size) { return reportMapper.selectList(Wrappers.<ReportPo>lambdaQuery().eq(status != null, ReportPo::getStatus, status).orderByDesc(ReportPo::getCreatedAt).last("LIMIT " + offset + "," + size)).stream().map(this::report).toList(); }
  @Override public long countReports(com.gvchat.common.enums.ReportStatus status) { return reportMapper.selectCount(Wrappers.<ReportPo>lambdaQuery().eq(status != null, ReportPo::getStatus, status)); }
  @Override public Report saveReport(Report value) { ReportPo po = reportPo(value); if (po.getId() == null) reportMapper.insert(po); else reportMapper.updateById(po); return report(po); }
  @Override public SensitiveWord saveSensitiveWord(SensitiveWord value) { SensitiveWordPo po = wordPo(value); if (po.getId() == null) sensitiveWordMapper.insert(po); else sensitiveWordMapper.updateById(po); return word(po); }
  @Override public Optional<SensitiveWord> findSensitiveWordById(Long id) { return Optional.ofNullable(sensitiveWordMapper.selectById(id)).map(this::word); }
  @Override public List<SensitiveWord> findSensitiveWords(int offset, int size) { return sensitiveWordMapper.selectList(Wrappers.<SensitiveWordPo>lambdaQuery().orderByDesc(SensitiveWordPo::getUpdatedAt).last("LIMIT " + offset + "," + size)).stream().map(this::word).toList(); }
  @Override public long countSensitiveWords() { return sensitiveWordMapper.selectCount(null); }
  @Override public void deleteSensitiveWord(Long id) { sensitiveWordMapper.deleteById(id); }
  @Override public List<SensitiveWord> findEnabledSensitiveWords() { return sensitiveWordMapper.selectList(Wrappers.<SensitiveWordPo>lambdaQuery().eq(SensitiveWordPo::getEnabled, true)).stream().map(this::word).toList(); }
  @Override public Violation saveViolation(Violation value) { ViolationPo po = violationPo(value); if (po.getId() == null) violationMapper.insert(po); else violationMapper.updateById(po); return violation(po); }
  @Override public List<Violation> findViolations(Long targetId, com.gvchat.common.enums.ViolationAction action, int offset, int size) { return violationMapper.selectList(Wrappers.<ViolationPo>lambdaQuery().eq(targetId != null, ViolationPo::getUserId, targetId).eq(action != null, ViolationPo::getAction, action).orderByDesc(ViolationPo::getCreatedAt).last("LIMIT " + offset + "," + size)).stream().map(this::violation).toList(); }
  @Override public long countViolations(Long targetId, com.gvchat.common.enums.ViolationAction action) { return violationMapper.selectCount(Wrappers.<ViolationPo>lambdaQuery().eq(targetId != null, ViolationPo::getUserId, targetId).eq(action != null, ViolationPo::getAction, action)); }
  @Override public List<MiniappServiceType> findAllTypes() { return typeMapper.selectList(Wrappers.<MiniappServiceTypePo>lambdaQuery().orderByAsc(MiniappServiceTypePo::getSortOrder, MiniappServiceTypePo::getId)).stream().map(this::type).toList(); }
  @Override public Optional<MiniappServiceType> findTypeById(Integer id) { return Optional.ofNullable(typeMapper.selectById(id)).map(this::type); }
  @Override public MiniappServiceType saveType(MiniappServiceType value) { MiniappServiceTypePo po = typePo(value); if (po.getId() == null) typeMapper.insert(po); else typeMapper.updateById(po); return type(po); }
  @Override public void deleteType(Integer id) { typeMapper.deleteById(id); }
  @Override public List<MiniappServiceItem> findPublishedConsumerItems() { return itemMapper.selectList(Wrappers.<MiniappServiceItemPo>lambdaQuery().eq(MiniappServiceItemPo::getStatus, true).eq(MiniappServiceItemPo::getAudience, MiniappServiceItem.AUDIENCE_CONSUMER).orderByDesc(MiniappServiceItemPo::getIsTop).orderByAsc(MiniappServiceItemPo::getSortOrder, MiniappServiceItemPo::getId)).stream().map(this::item).toList(); }
    @Override public List<MiniappServiceItem> findPublishedItems() { return itemMapper.selectList(Wrappers.<MiniappServiceItemPo>lambdaQuery().eq(MiniappServiceItemPo::getStatus, true).orderByDesc(MiniappServiceItemPo::getIsTop).orderByAsc(MiniappServiceItemPo::getSortOrder, MiniappServiceItemPo::getId)).stream().map(this::item).toList(); }
  @Override public List<MiniappServiceItem> searchPublishedItems(String keyword) { String like="%"+keyword+"%"; return itemMapper.selectList(Wrappers.<MiniappServiceItemPo>lambdaQuery().eq(MiniappServiceItemPo::getStatus, true).and(w -> w.like(MiniappServiceItemPo::getName, like).or().like(MiniappServiceItemPo::getIntroduction, like)).orderByDesc(MiniappServiceItemPo::getIsTop).orderByAsc(MiniappServiceItemPo::getSortOrder, MiniappServiceItemPo::getId)).stream().map(this::item).toList(); }
  @Override public List<MiniappServiceItem> findItemsByType(Integer id, int offset, int size) { return itemMapper.selectList(Wrappers.<MiniappServiceItemPo>lambdaQuery().eq(MiniappServiceItemPo::getTypeId, id).orderByDesc(MiniappServiceItemPo::getId).last("LIMIT " + offset + "," + size)).stream().map(this::item).toList(); }
  @Override public long countItemsByType(Integer id) { return itemMapper.selectCount(Wrappers.<MiniappServiceItemPo>lambdaQuery().eq(MiniappServiceItemPo::getTypeId, id)); }
  @Override public Optional<MiniappServiceItem> findItemById(Integer id) { return Optional.ofNullable(itemMapper.selectById(id)).map(this::item); }
  @Override public MiniappServiceItem saveItem(MiniappServiceItem value) { MiniappServiceItemPo po = itemPo(value); if (po.getId() == null) itemMapper.insert(po); else itemMapper.updateById(po); return item(po); }
  @Override public void deleteItem(Integer id) { itemMapper.deleteById(id); }
  private AdminConfiguration configuration(AdminConfigurationPo p) { return new AdminConfiguration(p.getId(),p.getConfigKey(),p.getConfigValue(),p.getConfigGroup(),p.getDescription(),p.getCreatedAt(),p.getUpdatedAt()); }
  private AdminConfigurationPo configurationPo(AdminConfiguration v) { AdminConfigurationPo p=new AdminConfigurationPo();p.setId(v.id());p.setConfigKey(v.configKey());p.setConfigValue(v.configValue());p.setConfigGroup(v.configGroup());p.setDescription(v.description());p.setCreatedAt(v.createdAt());p.setUpdatedAt(v.updatedAt());return p; }
  private Report report(ReportPo p) { return new Report(p.getId(),p.getReporterId(),p.getTargetId(),p.getReason(),p.getDescription(),p.getEvidence(),p.getStatus(),p.getHandledBy(),p.getHandleRemark(),p.getHandledAt(),p.getCreatedAt(),p.getUpdatedAt()); }
  private ReportPo reportPo(Report v) { ReportPo p=new ReportPo();p.setId(v.id());p.setReporterId(v.reporterId());p.setTargetId(v.targetId());p.setReason(v.reason());p.setDescription(v.description());p.setEvidence(v.evidence());p.setStatus(v.status());p.setHandledBy(v.handledBy());p.setHandleRemark(v.handleRemark());p.setHandledAt(v.handledAt());p.setCreatedAt(v.createdAt());p.setUpdatedAt(v.updatedAt());return p; }
  private SensitiveWord word(SensitiveWordPo p) { return new SensitiveWord(p.getId(),p.getWord(),p.getCategory(),p.getLevel(),p.getEnabled(),p.getCreatedAt(),p.getUpdatedAt()); }
  private SensitiveWordPo wordPo(SensitiveWord v) { SensitiveWordPo p=new SensitiveWordPo();p.setId(v.id());p.setWord(v.word());p.setCategory(v.category());p.setLevel(v.level());p.setEnabled(v.enabled());p.setCreatedAt(v.createdAt());p.setUpdatedAt(v.updatedAt());return p; }
  private Violation violation(ViolationPo p) { return new Violation(p.getId(),p.getUserId(),p.getReason(),p.getContent(),p.getMsgId(),p.getAction(),p.getRemark(),p.getCreatedAt()); }
  private ViolationPo violationPo(Violation v) { ViolationPo p=new ViolationPo();p.setId(v.id());p.setUserId(v.userId());p.setReason(v.reason());p.setContent(v.content());p.setMsgId(v.msgId());p.setAction(v.action());p.setRemark(v.remark());p.setCreatedAt(v.createdAt());return p; }
  private MiniappServiceType type(MiniappServiceTypePo p) { return new MiniappServiceType(p.getId(),p.getName(),p.getSortOrder(),p.getHidden(),p.getCreatedAt(),p.getUpdatedAt()); }
  private MiniappServiceTypePo typePo(MiniappServiceType v) { MiniappServiceTypePo p=new MiniappServiceTypePo();p.setId(v.id());p.setName(v.name());p.setSortOrder(v.sortOrder());p.setHidden(v.hidden());p.setCreatedAt(v.createdAt());p.setUpdatedAt(v.updatedAt());return p; }
  private MiniappServiceItem item(MiniappServiceItemPo p) { return new MiniappServiceItem(p.getId(),p.getTypeId(),p.getName(),p.getLink(),p.getIntroduction(),p.getIcon(),p.getStatus(),p.getIsTop(),p.getAudience(),p.getHidden(),p.getSortOrder(),p.getCreatedAt(),p.getUpdatedAt()); }
  private MiniappServiceItemPo itemPo(MiniappServiceItem v) { MiniappServiceItemPo p=new MiniappServiceItemPo();p.setId(v.id());p.setTypeId(v.typeId());p.setName(v.name());p.setLink(v.link());p.setIntroduction(v.introduction());p.setIcon(v.icon());p.setStatus(v.status());p.setIsTop(v.isTop());p.setAudience(v.audience());p.setHidden(v.hidden());p.setSortOrder(v.sortOrder());p.setCreatedAt(v.createdAt());p.setUpdatedAt(v.updatedAt());return p; }
}
