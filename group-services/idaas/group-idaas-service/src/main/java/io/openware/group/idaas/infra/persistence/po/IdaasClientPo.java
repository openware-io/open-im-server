package io.openware.group.idaas.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 接入方（OAuth client）。
 */
@Getter
@Setter
@TableName("idaas_client")
public class IdaasClientPo {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;

  /** 客户端ID（唯一） */
  private String clientId;

  private String clientSecret;

  /** 逗号分隔的重定向 URI */
  private String redirectUris;

  /** 状态 ENABLED/DISABLED */
  private String status;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
