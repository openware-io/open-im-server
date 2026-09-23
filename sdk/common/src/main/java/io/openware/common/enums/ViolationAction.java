package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

/** 违处罚动作类型*/
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum ViolationAction {
  /*
   */
  WARNED("warned"),
  MUTED("muted"),
  DISABLED("disabled"),
  ;

  private final String value;

  ViolationAction(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static ViolationAction fromValue(String v) {
    for (ViolationAction e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown ViolationAction: " + v);
  }
}
