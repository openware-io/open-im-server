package com.gvchat.im.admin.application;

import com.gvchat.im.admin.domain.configuration.AdminConfiguration;
import com.gvchat.im.admin.domain.miniapp.MiniappServiceItem;
import com.gvchat.im.admin.domain.miniapp.MiniappServiceType;
import com.gvchat.im.admin.domain.moderation.Report;
import com.gvchat.im.admin.domain.moderation.SensitiveWord;
import com.gvchat.im.admin.domain.moderation.Violation;
import com.gvchat.im.admin.media.MediaReferenceClient;
import com.gvchat.im.admin.domain.repository.AdminConfigurationRepository;
import com.gvchat.im.admin.domain.repository.MiniappRepository;
import com.gvchat.im.admin.domain.repository.ModerationRepository;
import com.gvchat.im.admin.integration.AdminReadClient;
import com.gvchat.common.dto.AdminUpdateUserStatusDto;
import com.gvchat.common.enums.ReportStatus;
import com.gvchat.common.enums.SensitiveWordCategory;
import com.gvchat.common.enums.SensitiveWordLevel;
import com.gvchat.common.enums.UserStatus;
import com.gvchat.common.enums.ViolationAction;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.api.admin.AdminUserResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AdminManagementApplicationService {
  private final AdminConfigurationRepository configurationRepository;
  private final ModerationRepository moderationRepository;
  private final MiniappRepository miniappRepository;
  private final MediaReferenceClient mediaReferences;
  private final AdminReadClient adminReadClient;

  public List<AdminConfiguration> configurations() { return configurationRepository.findAll(); }
  public AdminConfiguration upsertConfiguration(String key, String value, String description) { LocalDateTime now=LocalDateTime.now(); AdminConfiguration current=configurationRepository.findByKey(key).orElse(new AdminConfiguration(null,key,value,"default",description == null ? "" : description,now,now)); return configurationRepository.save(new AdminConfiguration(current.id(),key,value,current.configGroup(),description == null ? current.description() : description,current.createdAt(),now)); }
  public Map<String,Object> batchUpsertConfigurations(List<ConfigurationChange> changes) { changes.forEach(change -> upsertConfiguration(change.key(),change.value(),change.description())); return Map.of("ok",true,"count",changes.size()); }
  public boolean configurationEnabled(String key) { return configurationRepository.findByKey(key).map(value -> Boolean.parseBoolean(value.configValue())).orElse(false); }
  /** 读取配置值；缺失时返回 [defaultValue]（布尔）。 */
  public boolean configurationEnabled(String key, boolean defaultValue) {
    return configurationRepository.findByKey(key)
        .map(value -> Boolean.parseBoolean(value.configValue()))
        .orElse(defaultValue);
  }
  /** 读取字符串配置；缺失时返回 [defaultValue]。 */
  public String configurationValue(String key, String defaultValue) {
    return configurationRepository.findByKey(key)
        .map(AdminConfiguration::configValue)
        .filter(value -> value != null && !value.isBlank())
        .orElse(defaultValue);
  }
  /** 读取整数配置；缺失或非法时返回 [defaultValue]。 */
  public int configurationInt(String key, int defaultValue) {
    return configurationRepository.findByKey(key)
        .map(AdminConfiguration::configValue)
        .map(value -> { try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return defaultValue; } })
        .orElse(defaultValue);
  }
  public Report createReport(Long reporterId, Long targetId, String reason, String description, String evidence) { if (moderationRepository.hasPendingReport(reporterId,targetId)) throw new ApiException(HttpStatusCodes.CONFLICT,"Report already pending"); LocalDateTime now=LocalDateTime.now(); return moderationRepository.saveReport(new Report(null,reporterId,targetId,reason,description,evidence,ReportStatus.PENDING,null,null,null,now,now)); }
  public Report handleReport(Long adminId, Long id, ReportStatus status, String remark, ViolationAction action, String content, String msgId, String violationReason) { Report current=moderationRepository.findReportById(id).orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND,"Report not found")); LocalDateTime now=LocalDateTime.now(); if (action != null) { moderationRepository.saveViolation(new Violation(null,current.targetId(),violationReason == null || violationReason.isBlank() ? current.reason() : violationReason,content,msgId,action,remark,now)); if (msgId != null && !msgId.isBlank()) { try { adminReadClient.adminDeleteMessage(msgId); } catch (RuntimeException ex) { log.warn("Failed to delete reported message, msgId={}", msgId, ex); } } if (action == ViolationAction.MUTED || action == ViolationAction.DISABLED) { applyPunishment(adminId, current.targetId(), action); } } return moderationRepository.saveReport(new Report(current.id(),current.reporterId(),current.targetId(),current.reason(),current.description(),current.evidence(),status,adminId,remark,now,current.createdAt(),now)); }
  public long pendingReportCount() { return moderationRepository.countPendingReports(); }
  public PageResult<Report> reports(ReportStatus status, Integer page, Integer pageSize) { int actualPage=page==null?1:page; int actualSize=pageSize==null?20:pageSize; return PageResult.<Report>builder().items(moderationRepository.findReports(status,(actualPage-1)*actualSize,actualSize)).total(moderationRepository.countReports(status)).page(actualPage).pageSize(actualSize).build(); }
  public SensitiveWord createWord(String word, SensitiveWordCategory category, SensitiveWordLevel level) { LocalDateTime now=LocalDateTime.now(); return moderationRepository.saveSensitiveWord(new SensitiveWord(null,word,category,level == null ? SensitiveWordLevel.MEDIUM : level,true,now,now)); }
  public SensitiveWord updateWord(Long id, WordPatch patch) { SensitiveWord current=moderationRepository.findSensitiveWordById(id).orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND,"Word not found")); return moderationRepository.saveSensitiveWord(new SensitiveWord(id,patch.word()==null?current.word():patch.word(),patch.category()==null?current.category():patch.category(),patch.level()==null?current.level():patch.level(),patch.enabled()==null?current.enabled():patch.enabled(),current.createdAt(),LocalDateTime.now())); }
  public void deleteWord(Long id) { moderationRepository.deleteSensitiveWord(id); }
  public PageResult<SensitiveWord> sensitiveWords(Integer page, Integer pageSize) { int actualPage=page==null?1:page; int actualSize=pageSize==null?20:pageSize; return PageResult.<SensitiveWord>builder().items(moderationRepository.findSensitiveWords((actualPage-1)*actualSize,actualSize)).total(moderationRepository.countSensitiveWords()).page(actualPage).pageSize(actualSize).build(); }
  public List<SensitiveWord> enabledSensitiveWords() { return moderationRepository.findEnabledSensitiveWords(); }
  public Violation createViolation(Long userId,String reason,String content,String msgId,ViolationAction action,String remark) { return moderationRepository.saveViolation(new Violation(null,userId,reason,content,msgId,action,remark,LocalDateTime.now())); }
  public PageResult<Violation> violations(Long targetId, ViolationAction action, Integer page,Integer pageSize) { int actualPage=page==null?1:page; int actualSize=pageSize==null?20:pageSize; return PageResult.<Violation>builder().items(moderationRepository.findViolations(targetId,action,(actualPage-1)*actualSize,actualSize)).total(moderationRepository.countViolations(targetId,action)).page(actualPage).pageSize(actualSize).build(); }
  public List<MiniappServiceType> types() { return miniappRepository.findAllTypes(); }
  public MiniappServiceType createType(String name,Integer sortOrder,Integer hidden) { LocalDateTime now=LocalDateTime.now(); return miniappRepository.saveType(new MiniappServiceType(null,name,sortOrder==null?0:sortOrder,hiddenFlag(hidden,false),now,now)); }
  public MiniappServiceType updateType(Integer id,String name,Integer sortOrder,Integer hidden) { MiniappServiceType current=miniappRepository.findTypeById(id).orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND,"Type not found")); return miniappRepository.saveType(new MiniappServiceType(id,name==null?current.name():name,sortOrder==null?current.sortOrder():sortOrder,patchHidden(hidden,current.hidden()),current.createdAt(),LocalDateTime.now())); }
  public void sortTypes(List<TypeSort> sorts) { for (TypeSort sort : sorts) { miniappRepository.findTypeById(sort.id()).ifPresent(type -> miniappRepository.saveType(new MiniappServiceType(type.id(),type.name(),sort.sortOrder(),type.hidden(),type.createdAt(),LocalDateTime.now()))); } }
  public void deleteType(Integer id) { if (miniappRepository.findTypeById(id).isEmpty()) throw new ApiException(HttpStatusCodes.NOT_FOUND,"Type not found"); if (miniappRepository.countItemsByType(id) > 0) throw new ApiException(HttpStatusCodes.CONFLICT,"Type still has services"); miniappRepository.deleteType(id); }
  public PageResult<MiniappServiceItem> items(Integer typeId,int page,int pageSize) { return PageResult.<MiniappServiceItem>builder().items(miniappRepository.findItemsByType(typeId,(page-1)*pageSize,pageSize)).total(miniappRepository.countItemsByType(typeId)).page(page).pageSize(pageSize).build(); }
  public List<MiniappServiceItem> publishedConsumerItems() { return miniappRepository.findPublishedConsumerItems(); }
  public List<MiniappServiceItem> searchPublishedItems(String keyword) { return miniappRepository.searchPublishedItems(keyword); }
  public String publicItemIconUrl(MiniappServiceItem item) { return mediaReferences.accessUrl(0L, item.icon(), String.valueOf(item.id())); }
  public MiniappServiceItem createItem(long ownerId, ItemChange change) { if (miniappRepository.findTypeById(change.typeId()).isEmpty()) throw new ApiException(HttpStatusCodes.BAD_REQUEST,"Type not found"); LocalDateTime now=LocalDateTime.now(); MiniappServiceItem saved = miniappRepository.saveItem(new MiniappServiceItem(null,change.typeId(),change.name(),change.link(),empty(change.introduction()),change.icon(),change.status()==null || change.status()!=0,change.isTop()!=null && change.isTop()!=0,audience(change.audience()),hiddenFlag(change.hidden(),false),change.sortOrder()==null?0:change.sortOrder(),now,now)); mediaReferences.authorizeAndBind(ownerId, saved.icon(), String.valueOf(saved.id())); return saved; }
  public MiniappServiceItem updateItem(long ownerId, Integer id,ItemPatch patch) { MiniappServiceItem current=miniappRepository.findItemById(id).orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND,"Item not found")); Integer nextTypeId=patch.typeId()==null?current.typeId():patch.typeId(); if (miniappRepository.findTypeById(nextTypeId).isEmpty()) throw new ApiException(HttpStatusCodes.BAD_REQUEST,"Type not found"); String nextAudience=patch.audience()==null?current.audience():audience(patch.audience()); MiniappServiceItem saved = miniappRepository.saveItem(new MiniappServiceItem(id,nextTypeId,patch.name()==null?current.name():patch.name(),patch.link()==null?current.link():patch.link(),patch.introduction()==null?current.introduction():patch.introduction(),patch.icon()==null?current.icon():patch.icon(),patch.status()==null?current.status():patch.status()!=0,patch.isTop()==null?current.isTop():patch.isTop()!=0,nextAudience,patchHidden(patch.hidden(),current.hidden()),patch.sortOrder()==null?current.sortOrder():patch.sortOrder(),current.createdAt(),LocalDateTime.now())); if (!java.util.Objects.equals(current.icon(), saved.icon())) { mediaReferences.authorizeAndBind(ownerId, saved.icon(), String.valueOf(id)); mediaReferences.unbind(current.icon(), String.valueOf(id)); } return saved; }
  public void deleteItem(Integer id) { MiniappServiceItem current=miniappRepository.findItemById(id).orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND,"Item not found")); mediaReferences.unbind(current.icon(), String.valueOf(id)); miniappRepository.deleteItem(id); }
  /** 快捷启用/停用：切换 status（true↔false），返回最新状态。 */
  public MiniappServiceItem toggleItem(Integer id) { MiniappServiceItem current=miniappRepository.findItemById(id).orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND,"Item not found")); return miniappRepository.saveItem(new MiniappServiceItem(id,current.typeId(),current.name(),current.link(),current.introduction(),current.icon(),current.status()==null || !current.status(),current.isTop(),current.audience(),current.hidden(),current.sortOrder(),current.createdAt(),LocalDateTime.now())); }
  /** 快捷启用/停用：显式设置 status（1=启用，0=停用），幂等，返回最新状态。 */
  public MiniappServiceItem setItemStatus(Integer id, Integer status) { if (status == null || (status != 0 && status != 1)) throw new ApiException(HttpStatusCodes.BAD_REQUEST,"Invalid status"); MiniappServiceItem current=miniappRepository.findItemById(id).orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND,"Item not found")); boolean enabled=status == 1; if (java.util.Objects.equals(current.status(), enabled)) return current; return miniappRepository.saveItem(new MiniappServiceItem(id,current.typeId(),current.name(),current.link(),current.introduction(),current.icon(),enabled,current.isTop(),current.audience(),current.hidden(),current.sortOrder(),current.createdAt(),LocalDateTime.now())); }
  /** 显式设置隐藏（1=隐藏，0=展示），幂等，返回最新状态。隐藏只影响列表展示，不影响搜索与固定。 */
  public MiniappServiceItem setItemHidden(Integer id, Integer hidden) { if (hidden == null || (hidden != 0 && hidden != 1)) throw new ApiException(HttpStatusCodes.BAD_REQUEST,"Invalid hidden"); MiniappServiceItem current=miniappRepository.findItemById(id).orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND,"Item not found")); boolean next=hidden == 1; if (java.util.Objects.equals(current.hidden(), next)) return current; return miniappRepository.saveItem(new MiniappServiceItem(id,current.typeId(),current.name(),current.link(),current.introduction(),current.icon(),current.status(),current.isTop(),current.audience(),next,current.sortOrder(),current.createdAt(),LocalDateTime.now())); }
  /** 新建时的隐藏标记：null 视为不隐藏（保持既有默认行为）。 */
  private static Boolean hiddenFlag(Integer hidden, boolean fallback) { if (hidden == null) return fallback; return hidden != 0; }
  /** 更新时的隐藏标记：null 表示未提交该字段，保持原值。 */
  private static Boolean patchHidden(Integer hidden, Boolean current) { if (hidden == null) return current; return hidden != 0; }
  private String audience(String value) { if (value == null || value.isBlank()) return MiniappServiceItem.AUDIENCE_CONSUMER; String normalized=value.trim(); if (!MiniappServiceItem.AUDIENCE_CONSUMER.equals(normalized) && !MiniappServiceItem.AUDIENCE_OPERATOR.equals(normalized)) throw new ApiException(HttpStatusCodes.BAD_REQUEST,"Invalid audience"); return normalized; }
  private String empty(String value) { return value == null ? "" : value; }
  private void applyPunishment(Long adminId, Long targetId, ViolationAction action) {
    AdminUserResponse user = adminReadClient.getUser(targetId);
    UserStatus targetStatus = action == ViolationAction.MUTED ? UserStatus.MUTED : UserStatus.DISABLED;
    adminReadClient.updateUserStatus(targetId, adminId, AdminUpdateUserStatusDto.builder()
        .status(targetStatus)
        .expectedStatusVersion(user.getStatusVersion())
        .idempotencyKey(UUID.randomUUID().toString())
        .reason("举报处理处罚")
        .correlationId(UUID.randomUUID().toString())
        .build());
  }
  public record ConfigurationChange(String key,String value,String description) {}
  public record WordPatch(String word,SensitiveWordCategory category,SensitiveWordLevel level,Boolean enabled) {}
  public record TypeSort(Integer id,Integer sortOrder) {}
  public record ItemChange(Integer typeId,String name,String link,String introduction,String icon,Integer status,Integer isTop,String audience,Integer hidden,Integer sortOrder) {}
  public record ItemPatch(Integer typeId,String name,String link,String introduction,String icon,Integer status,Integer isTop,String audience,Integer hidden,Integer sortOrder) {}
}
