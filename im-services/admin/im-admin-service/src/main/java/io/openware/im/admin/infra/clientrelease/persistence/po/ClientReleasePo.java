package io.openware.im.admin.infra.clientrelease.persistence.po;
import com.baomidou.mybatisplus.annotation.*;
import io.openware.im.admin.domain.clientrelease.model.*;
import io.openware.im.admin.infra.clientrelease.persistence.typehandler.*;
import java.time.LocalDateTime;
import lombok.Getter; import lombok.Setter;
@Getter @Setter @TableName(value = "adm_client_release", autoResultMap = true) public class ClientReleasePo {
  @TableId(type = IdType.AUTO) private Long id;
  @TableField(typeHandler = ReleasePlatformTypeHandler.class) private ReleasePlatform platform;
  @TableField(typeHandler = ReleaseChannelTypeHandler.class) private ReleaseChannel channel;
  private String version; @TableField("build_number") private Long buildNumber;
  @TableField(typeHandler = ReleaseStatusTypeHandler.class) private ReleaseStatus status;
  private Boolean mandatory; @TableField("rollout_percent") private Integer rolloutPercent; @TableField("rollout_salt") private String rolloutSalt;
  @TableField("scheduled_at") private LocalDateTime scheduledAt; @TableField("published_at") private LocalDateTime publishedAt;
  @TableField("release_notes") private String releaseNotes; @TableField("store_url") private String storeUrl;
  @TableField("compatibility_json") private String compatibilityJson; @TableField("row_version") private Long rowVersion;
  @TableField("created_by") private Long createdBy; @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_by") private Long updatedBy; @TableField("updated_at") private LocalDateTime updatedAt;
}
