package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum FriendRequestStatus {
  /*
   */
  PENDING("pending"),
  ACCEPTED("accepted"),
  REJECTED("rejected"),
  ;

  private final String value;

  FriendRequestStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static FriendRequestStatus fromValue(String v) {
    for (FriendRequestStatus e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown FriendRequestStatus: " + v);
  }
}
