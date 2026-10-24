package io.openware.platform.tenant.infra.persistence.row;

import lombok.Getter;
import lombok.Setter;

/** 租户业务权益行。 */
@Getter
@Setter
public class EntitlementRow {
  private String businessType;
  private String status;
  private String source;
}
