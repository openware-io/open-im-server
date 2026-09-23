package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum GroupStatus {
  /*
   */
  ACTIVE("active"),
  DISSOLVED("dissolved"),
  ;

  private final String value;

  GroupStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static GroupStatus fromValue(String v) {
    for (GroupStatus e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown GroupStatus: " + v);
  }
}
