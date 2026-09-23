package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum UserStatus {
  /*
   */
  ACTIVE("active"),
  MUTED("muted"),
  DISABLED("disabled"),
  ;

  private final String value;

  UserStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static UserStatus fromValue(String v) {
    for (UserStatus e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown UserStatus: " + v);
  }
}
