package io.openware.im.admin.api;

import io.openware.common.enums.ReportStatus;
import io.openware.common.enums.SensitiveWordCategory;
import io.openware.common.enums.SensitiveWordLevel;
import io.openware.common.enums.ViolationAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public final class AdminManagementDtos {
  private AdminManagementDtos() {}
  public record ConfigRequest(@NotBlank String configKey,@NotBlank String configValue,String description) {}
  public record ConfigResponse(Integer id,String configKey,String configValue,String configGroup,String description,LocalDateTime createdAt,LocalDateTime updatedAt) {}
  public record ReportCreateRequest(@NotNull Long targetId,@NotBlank String reason,String description,String evidence) {}
  public record ReportHandleRequest(@NotNull ReportStatus status,String remark,ViolationAction action,String content,String msgId,String violationReason) {}
  public record ReportResponse(Long id,Long reporterId,Long targetId,String reason,String description,String evidence,ReportStatus status,Long handledBy,String handleRemark,LocalDateTime handledAt,LocalDateTime createdAt,LocalDateTime updatedAt,String reporterNickname,String reporterAvatar,String targetNickname,String targetAvatar,String handledByNickname) {}
  public record SensitiveWordCreateRequest(@NotBlank String word,@NotNull SensitiveWordCategory category,SensitiveWordLevel level) {}
  public record SensitiveWordUpdateRequest(String word,SensitiveWordCategory category,SensitiveWordLevel level,Boolean enabled) {}
  public record SensitiveWordResponse(Long id,String word,SensitiveWordCategory category,SensitiveWordLevel level,Boolean enabled,LocalDateTime createdAt,LocalDateTime updatedAt) {}
  public record ViolationCreateRequest(@NotNull Long userId,@NotBlank String reason,String content,String msgId,@NotNull ViolationAction action,String remark) {}
  public record ViolationResponse(Long id,Long userId,String reason,String content,String msgId,ViolationAction action,String remark,LocalDateTime createdAt,String userNickname,String userAvatar) {}
  public record TypeCreateRequest(@NotBlank String name,Integer sortOrder,Integer hidden) {}
  public record TypeUpdateRequest(String name,Integer sortOrder,Integer hidden) {}
  public record TypeResponse(Integer id,String name,Integer sortOrder,Boolean hidden,LocalDateTime createdAt,LocalDateTime updatedAt) {}
  public record ItemCreateRequest(@NotNull Integer typeId,@NotBlank String name,@NotBlank String link,String introduction,String icon,Integer status,Integer isTop,String audience,Integer hidden,Integer sortOrder) {}
  public record ItemUpdateRequest(Integer typeId,String name,String link,String introduction,String icon,Integer status,Integer isTop,String audience,Integer hidden,Integer sortOrder) {}
  public record ItemResponse(Integer id,Integer typeId,String name,String link,String introduction,String icon,Integer status,Boolean isTop,String audience,Boolean hidden,Integer sortOrder,LocalDateTime createdAt,LocalDateTime updatedAt) {}
  public record ItemStatusRequest(@NotNull Integer status) {}
  public record ItemHiddenRequest(@NotNull Integer hidden) {}
}
