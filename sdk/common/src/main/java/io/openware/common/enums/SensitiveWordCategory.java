package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum SensitiveWordCategory {
  /*
   */
  POLITICS("politics"),
  PORN("porn"),
  ADS("ads"),
  ABUSE("abuse"),
  ;

  private final String value;

  SensitiveWordCategory(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static SensitiveWordCategory fromValue(String v) {
    for (SensitiveWordCategory e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown SensitiveWordCategory: " + v);
  }
}
