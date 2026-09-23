package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum SensitiveWordLevel {
  /*
   */
  LOW("low"),
  MEDIUM("medium"),
  HIGH("high"),
  ;

  private final String value;

  SensitiveWordLevel(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static SensitiveWordLevel fromValue(String v) {
    for (SensitiveWordLevel e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown SensitiveWordLevel: " + v);
  }
}
