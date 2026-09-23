package io.openware.im.admin.api;

import io.openware.im.admin.application.AdminManagementApplicationService;
import io.openware.im.admin.application.AdminManagementApplicationService.ConfigurationChange;
import io.openware.im.admin.application.AdminManagementApplicationService.ItemChange;
import io.openware.im.admin.application.AdminManagementApplicationService.ItemPatch;
import io.openware.im.admin.application.AdminManagementApplicationService.TypeSort;
import io.openware.im.admin.application.AdminManagementApplicationService.WordPatch;
import io.openware.im.admin.domain.configuration.AdminConfiguration;
import io.openware.im.admin.domain.miniapp.MiniappServiceItem;
import io.openware.im.admin.domain.miniapp.MiniappServiceType;
import io.openware.im.admin.domain.moderation.Report;
import io.openware.im.admin.domain.moderation.SensitiveWord;
import io.openware.im.admin.domain.moderation.Violation;
import io.openware.im.admin.integration.AdminReadClient;
import io.openware.common.dto.PageResult;
import io.openware.infrastructure.security.SecurityUser;
import io.openware.im.user.api.authorization.UserProfileSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminManagementController {
  private final AdminManagementApplicationService service;
  private final AdminReadClient adminReadClient;

  @GetMapping("/admin/config") public List<AdminManagementDtos.ConfigResponse> configurations() { return service.configurations().stream().map(this::config).toList(); }
  @PutMapping("/admin/config") public AdminManagementDtos.ConfigResponse upsertConfiguration(@Valid @RequestBody AdminManagementDtos.ConfigRequest request) { return config(service.upsertConfiguration(request.configKey(),request.configValue(),request.description())); }
  @PostMapping("/admin/config/batch") public Map<String,Object> batchConfigurations(@Valid @RequestBody ConfigBatchRequest request) { return service.batchUpsertConfigurations(request.configs().stream().map(value -> new ConfigurationChange(value.configKey(),value.configValue(),value.description())).toList()); }
  @GetMapping("/config/client") public Map<String,Object> clientConfig() {
    // 全部从 DB 读取（缺失用默认值），键名与 App 契约对齐；admin 配置页保存后即时生效。
    return Map.of(
        "app", Map.of(
            "name", service.configurationValue("app.name", "WV Chat"),
            "announcement", service.configurationValue("app.announcement", "")),
        "feature", Map.ofEntries(
            Map.entry("privateChatEnabled", service.configurationEnabled("feature.privateChatEnabled", true)),
            Map.entry("groupChatEnabled", service.configurationEnabled("feature.groupChatEnabled", true)),
            Map.entry("recallEnabled", service.configurationEnabled("feature.recallEnabled", true)),
            Map.entry("readReceiptEnabled", service.configurationEnabled("feature.readReceiptEnabled", true)),
            Map.entry("voiceCallEnabled", service.configurationEnabled("feature.voiceCallEnabled", true)),
            Map.entry("videoCallEnabled", service.configurationEnabled("feature.videoCallEnabled", true)),
            Map.entry("channelEnabled", service.configurationEnabled("feature.channelEnabled", true)),
            Map.entry("secretChatEnabled", service.configurationEnabled("feature.secretChatEnabled", true)),
            Map.entry("secretGroupChatEnabled", service.configurationEnabled("feature.secretGroupChatEnabled", true)),
            Map.entry("groupDeleteEveryoneEnabled", service.configurationEnabled("feature.groupDeleteEveryoneEnabled", true)),
            Map.entry("groupEditMessageEnabled", service.configurationEnabled("feature.groupEditMessageEnabled", true)),
            Map.entry("groupAnonymityEnabled", service.configurationEnabled("feature.groupAnonymityEnabled", false)),
            Map.entry("groupInviteLinkEnabled", service.configurationEnabled("feature.groupInviteLinkEnabled", true)),
            Map.entry("groupPinnedEnabled", service.configurationEnabled("feature.groupPinnedEnabled", true)),
            Map.entry("chatDeleteEnabled", service.configurationEnabled("feature.chatDeleteEnabled", true)),
            Map.entry("hideGroupMemberInfo", service.configurationEnabled("feature.hideGroupMemberInfo", true))),
        "rtc", Map.of(
            "videoQuality", service.configurationValue("rtc.videoQuality", "720p"),
            "videoBitrate", service.configurationInt("rtc.videoBitrate", 1500),
            "audioBitrate", service.configurationInt("rtc.audioBitrate", 64),
            "maxCallDuration", service.configurationInt("rtc.maxCallDuration", 120)),
        "upload", Map.of(
            "maxImageSizeMB", service.configurationInt("upload.maxImageSize", 10),
            "maxFileSizeMB", service.configurationInt("upload.maxFileSize", 50),
            "maxVideoSizeMB", service.configurationInt("upload.maxVideoSize", 100)),
        "push", Map.of(
            "enabled", service.configurationEnabled("push.enabled", true),
            "apnsEnabled", service.configurationEnabled("push.apnsEnabled", true),
            "fcmEnabled", service.configurationEnabled("push.fcmEnabled", false),
            "jpushEnabled", service.configurationEnabled("push.jpushEnabled", true)));
  }

  @PostMapping("/reports") public AdminManagementDtos.ReportResponse createReport(@AuthenticationPrincipal SecurityUser user,@Valid @RequestBody AdminManagementDtos.ReportCreateRequest request) { return report(service.createReport(user.getId(),request.targetId(),request.reason(),request.description(),request.evidence())); }
  @PutMapping("/admin/security/reports/{id}") public AdminManagementDtos.ReportResponse handleReport(@AuthenticationPrincipal SecurityUser user,@PathVariable Long id,@Valid @RequestBody AdminManagementDtos.ReportHandleRequest request) { return report(service.handleReport(user.getId(),id,request.status(),request.remark(),request.action(),request.content(),request.msgId(),request.violationReason())); }
  @GetMapping("/admin/security/reports") public PageResult<AdminManagementDtos.ReportResponse> reports(@ModelAttribute ReportListRequest request) { return reports(service.reports(reportStatus(request.status()),request.page(),request.pageSize())); }
  @GetMapping("/admin/security/reports/pending/count") public Map<String,Long> pendingReports() { return Map.of("count",service.pendingReportCount()); }
  @GetMapping("/admin/security/sensitive-words") public PageResult<AdminManagementDtos.SensitiveWordResponse> sensitiveWords(@ModelAttribute PageRequest request) { return words(service.sensitiveWords(request.page(),request.pageSize())); }
  @PostMapping("/admin/security/sensitive-words") public AdminManagementDtos.SensitiveWordResponse createWord(@Valid @RequestBody AdminManagementDtos.SensitiveWordCreateRequest request) { return word(service.createWord(request.word(),request.category(),request.level())); }
  @PutMapping("/admin/security/sensitive-words/{id}") public AdminManagementDtos.SensitiveWordResponse updateWord(@PathVariable Long id,@Valid @RequestBody AdminManagementDtos.SensitiveWordUpdateRequest request) { return word(service.updateWord(id,new WordPatch(request.word(),request.category(),request.level(),request.enabled()))); }
  @DeleteMapping("/admin/security/sensitive-words/{id}") public void deleteWord(@PathVariable Long id) { service.deleteWord(id); }
  @PostMapping("/admin/security/violations") public AdminManagementDtos.ViolationResponse createViolation(@Valid @RequestBody AdminManagementDtos.ViolationCreateRequest request) { return violation(service.createViolation(request.userId(),request.reason(),request.content(),request.msgId(),request.action(),request.remark())); }
  @GetMapping("/admin/security/violations") public PageResult<AdminManagementDtos.ViolationResponse> violations(@ModelAttribute ViolationListRequest request) { return violations(service.violations(request.targetId(),violationAction(request.action()),request.page(),request.pageSize())); }

  @GetMapping("/admin/miniapp/service-types") public List<AdminManagementDtos.TypeResponse> types() { return service.types().stream().map(this::type).toList(); }
  @PostMapping("/admin/miniapp/service-types") public AdminManagementDtos.TypeResponse createType(@Valid @RequestBody AdminManagementDtos.TypeCreateRequest request) { return type(service.createType(request.name(),request.sortOrder(),request.hidden())); }
  @PutMapping("/admin/miniapp/service-types/{id}") public AdminManagementDtos.TypeResponse updateType(@PathVariable Integer id,@Valid @RequestBody AdminManagementDtos.TypeUpdateRequest request) { return type(service.updateType(id,request.name(),request.sortOrder(),request.hidden())); }
  @DeleteMapping("/admin/miniapp/service-types/{id}") public void deleteType(@PathVariable Integer id) { service.deleteType(id); }
  @PostMapping("/admin/miniapp/service-types/sort") public Map<String,Boolean> sortTypes(@Valid @RequestBody TypeSortRequest request) { service.sortTypes(request.items().stream().map(value -> new TypeSort(value.id(),value.sortOrder())).toList()); return Map.of("ok",true); }
  @GetMapping("/admin/miniapp/services") public PageResult<AdminManagementDtos.ItemResponse> items(@RequestParam Integer typeId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return items(service.items(typeId,page,pageSize)); }
  @PostMapping("/admin/miniapp/services") public AdminManagementDtos.ItemResponse createItem(@AuthenticationPrincipal SecurityUser user,@Valid @RequestBody AdminManagementDtos.ItemCreateRequest request) { return item(service.createItem(user.getId(),new ItemChange(request.typeId(),request.name(),request.link(),request.introduction(),request.icon(),request.status(),request.isTop(),request.audience(),request.hidden(),request.sortOrder()))); }
  @PutMapping("/admin/miniapp/services/{id}") public AdminManagementDtos.ItemResponse updateItem(@AuthenticationPrincipal SecurityUser user,@PathVariable Integer id,@Valid @RequestBody AdminManagementDtos.ItemUpdateRequest request) { return item(service.updateItem(user.getId(),id,new ItemPatch(request.typeId(),request.name(),request.link(),request.introduction(),request.icon(),request.status(),request.isTop(),request.audience(),request.hidden(),request.sortOrder()))); }
  @DeleteMapping("/admin/miniapp/services/{id}") public void deleteItem(@PathVariable Integer id) { service.deleteItem(id); }
  @PutMapping("/admin/miniapp/services/{id}/status") public AdminManagementDtos.ItemResponse setItemStatus(@PathVariable Integer id,@Valid @RequestBody AdminManagementDtos.ItemStatusRequest request) { return item(service.setItemStatus(id,request.status())); }
  @PutMapping("/admin/miniapp/services/{id}/hidden") public AdminManagementDtos.ItemResponse setItemHidden(@PathVariable Integer id,@Valid @RequestBody AdminManagementDtos.ItemHiddenRequest request) { return item(service.setItemHidden(id,request.hidden())); }
  @PostMapping("/admin/miniapp/services/{id}/toggle") public AdminManagementDtos.ItemResponse toggleItem(@PathVariable Integer id) { return item(service.toggleItem(id)); }
  /**
   * 服务 Tab 数据。
   *
   * <p>无 keyword：按分组返回**列表展示用**的小程序——隐藏分组与隐藏小程序都排除在外。
   * 有 keyword：搜索**不受隐藏影响**，隐藏项同样能被搜到（隐藏的语义是"不进列表"，不是"不可用"）；
   * 客户端可从搜索结果固定到快捷应用区，固定项由本地存储驱动，因此固定后照样展示。
   */
  @GetMapping("/miniapp/services") public List<Map<String,Object>> published(@RequestParam(required=false) String keyword) {
    if (keyword != null && !keyword.isBlank()) {
      return service.searchPublishedItems(keyword.trim()).stream().map(this::publicItem).toList();
    }
    Map<Integer, MiniappServiceType> typesById = service.types().stream().collect(java.util.stream.Collectors.toMap(MiniappServiceType::id, t -> t, (a, b) -> a, java.util.LinkedHashMap::new));
    Map<Integer, List<MiniappServiceItem>> byType = service.publishedConsumerItems().stream().filter(item -> !item.hiddenFlag()).collect(java.util.stream.Collectors.groupingBy(MiniappServiceItem::typeId));
    List<Map<String,Object>> result = new java.util.ArrayList<>();
    for (MiniappServiceType type : typesById.values()) {
      if (Boolean.TRUE.equals(type.hidden())) continue;
      List<MiniappServiceItem> items = byType.get(type.id());
      if (items == null || items.isEmpty()) continue;
      result.add(Map.of("typeName", type.name(), "items", items.stream().map(this::publicItem).toList()));
    }
    return result;
  }

  private AdminManagementDtos.ConfigResponse config(AdminConfiguration value) { return new AdminManagementDtos.ConfigResponse(value.id(),value.configKey(),value.configValue(),value.configGroup(),value.description(),value.createdAt(),value.updatedAt()); }
  private AdminManagementDtos.ReportResponse report(Report value) { return report(value, Map.of()); }
  private AdminManagementDtos.ReportResponse report(Report value, Map<Long, UserProfileSummary> profiles) {
    UserProfileSummary reporter = profiles.get(value.reporterId());
    UserProfileSummary target = profiles.get(value.targetId());
    UserProfileSummary handler = value.handledBy() == null ? null : profiles.get(value.handledBy());
    return new AdminManagementDtos.ReportResponse(value.id(),value.reporterId(),value.targetId(),value.reason(),value.description(),value.evidence(),value.status(),value.handledBy(),value.handleRemark(),value.handledAt(),value.createdAt(),value.updatedAt(),
        reporter == null ? null : reporter.nickname(), reporter == null ? null : reporter.avatar(),
        target == null ? null : target.nickname(), target == null ? null : target.avatar(),
        handler == null ? null : handler.nickname());
  }
  private AdminManagementDtos.SensitiveWordResponse word(SensitiveWord value) { return new AdminManagementDtos.SensitiveWordResponse(value.id(),value.word(),value.category(),value.level(),value.enabled(),value.createdAt(),value.updatedAt()); }
  private AdminManagementDtos.ViolationResponse violation(Violation value) { return violation(value, Map.of()); }
  private AdminManagementDtos.ViolationResponse violation(Violation value, Map<Long, UserProfileSummary> profiles) {
    UserProfileSummary user = profiles.get(value.userId());
    return new AdminManagementDtos.ViolationResponse(value.id(),value.userId(),value.reason(),value.content(),value.msgId(),value.action(),value.remark(),value.createdAt(),
        user == null ? null : user.nickname(), user == null ? null : user.avatar());
  }
  private AdminManagementDtos.TypeResponse type(MiniappServiceType value) { return new AdminManagementDtos.TypeResponse(value.id(),value.name(),value.sortOrder(),value.hidden(),value.createdAt(),value.updatedAt()); }
  private AdminManagementDtos.ItemResponse item(MiniappServiceItem value) { return new AdminManagementDtos.ItemResponse(value.id(),value.typeId(),value.name(),value.link(),value.introduction(),value.icon(),value.status()==null?null:(value.status()?1:0),value.isTop(),value.audience(),value.hidden(),value.sortOrder(),value.createdAt(),value.updatedAt()); }
  private Map<String,Object> publicItem(MiniappServiceItem value) { return Map.ofEntries(
      Map.entry("id", value.id()), Map.entry("typeId", value.typeId()), Map.entry("name", value.name()),
      Map.entry("link", value.link()), Map.entry("introduction", value.introduction()),
      Map.entry("iconObjectId", value.icon() == null ? "" : value.icon()),
      Map.entry("iconUrl", service.publicItemIconUrl(value)), Map.entry("isTop", value.isTop()),
      Map.entry("audience", value.audience() == null ? MiniappServiceItem.AUDIENCE_CONSUMER : value.audience()),
      Map.entry("hidden", value.hiddenFlag()),
      Map.entry("sortOrder", value.sortOrder())); }
  private PageResult<AdminManagementDtos.ViolationResponse> violations(PageResult<Violation> value) {
    Map<Long, UserProfileSummary> profiles = adminReadClient.listProfileSummaries(value.getItems().stream()
        .map(Violation::userId).filter(java.util.Objects::nonNull).distinct().toList());
    return PageResult.<AdminManagementDtos.ViolationResponse>builder().items(value.getItems().stream().map(item -> violation(item, profiles)).toList()).total(value.getTotal()).page(value.getPage()).pageSize(value.getPageSize()).updatedAt(value.getUpdatedAt()).build(); }
  private PageResult<AdminManagementDtos.ReportResponse> reports(PageResult<Report> value) {
    Map<Long, UserProfileSummary> profiles = adminReadClient.listProfileSummaries(value.getItems().stream()
        .flatMap(item -> java.util.stream.Stream.of(item.reporterId(), item.targetId(), item.handledBy()))
        .filter(java.util.Objects::nonNull).distinct().toList());
    return PageResult.<AdminManagementDtos.ReportResponse>builder().items(value.getItems().stream().map(item -> report(item, profiles)).toList()).total(value.getTotal()).page(value.getPage()).pageSize(value.getPageSize()).updatedAt(value.getUpdatedAt()).build(); }
  private PageResult<AdminManagementDtos.SensitiveWordResponse> words(PageResult<SensitiveWord> value) { return PageResult.<AdminManagementDtos.SensitiveWordResponse>builder().items(value.getItems().stream().map(this::word).toList()).total(value.getTotal()).page(value.getPage()).pageSize(value.getPageSize()).updatedAt(value.getUpdatedAt()).build(); }
  private PageResult<AdminManagementDtos.ItemResponse> items(PageResult<MiniappServiceItem> value) { return PageResult.<AdminManagementDtos.ItemResponse>builder().items(value.getItems().stream().map(this::item).toList()).total(value.getTotal()).page(value.getPage()).pageSize(value.getPageSize()).updatedAt(value.getUpdatedAt()).build(); }
  private io.openware.common.enums.ViolationAction violationAction(String value) { if (value == null || value.isBlank()) return null; try { return io.openware.common.enums.ViolationAction.fromValue(value); } catch (IllegalArgumentException exception) { throw new io.openware.common.exception.ApiException(io.openware.common.http.HttpStatusCodes.BAD_REQUEST,"Invalid violation action"); } }
  private io.openware.common.enums.ReportStatus reportStatus(String value) { if (value == null || value.isBlank()) return null; try { return io.openware.common.enums.ReportStatus.fromValue(value); } catch (IllegalArgumentException exception) { throw new io.openware.common.exception.ApiException(io.openware.common.http.HttpStatusCodes.BAD_REQUEST,"Invalid report status"); } }
  public record ConfigBatchRequest(@NotNull @Valid List<AdminManagementDtos.ConfigRequest> configs) {}
  public record ViolationListRequest(Long targetId,String action,Integer page,Integer pageSize) {}
  public record ReportListRequest(String status,Integer page,Integer pageSize) {}
  public record PageRequest(Integer page,Integer pageSize) {}
  public record TypeSortRequest(@NotNull @Valid List<TypeSortItem> items) {}
  public record TypeSortItem(@NotNull Integer id,@NotNull Integer sortOrder) {}
}
