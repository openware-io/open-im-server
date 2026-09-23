package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum ReportStatus {
  /*
   */
  PENDING("pending"),
  PROCESSING("processing"),
  RESOLVED("resolved"),
  DISMISSED("dismissed"),
  ;

  private final String value;

  ReportStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static ReportStatus fromValue(String v) {
    for (ReportStatus e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown ReportStatus: " + v);
  }
}
