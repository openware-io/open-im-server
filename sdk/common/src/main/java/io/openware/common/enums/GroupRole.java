package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum GroupRole {
  /*
   */
  OWNER("owner"),
  ADMIN("admin"),
  MEMBER("member"),
  ;

  private final String value;

  GroupRole(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static GroupRole fromValue(String v) {
    for (GroupRole e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown GroupRole: " + v);
  }
}
