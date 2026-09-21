package com.gvchat.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

/** 用户角色：区分普通用户与管理员。 */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum UserRole {
  /*
   * USER 表示普通用户；ADMIN 表示管理员。
   */
  USER("user"),
  ADMIN("admin"),
  ;

  private final String value;

  UserRole(String value) {
    this.value = value;
  }

  /** 返回 JSON 和数据库使用的字符串值。 */
  @JsonValue
  public String getValue() {
    return value;
  }

  /** 从字符串值解析枚举常量。 */
  @JsonCreator
  public static UserRole fromValue(String v) {
    for (UserRole e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown UserRole: " + v);
  }
}
