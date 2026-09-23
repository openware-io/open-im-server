package io.openware.infrastructure.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 审计详情脱敏器：把任意「详情」转成**合法 JSON**，并清掉隐私与凭据字段。
 *
 * <p>合规口径（SAAS_PLATFORM_02 §9.4 / KTV_BUSINESS_01 §7）：
 * <ul>
 *   <li>{@code detail_json} 不得包含密码、token、密钥、完整手机号、证件号；</li>
 *   <li>敏感字段整体替换为 {@code [REDACTED]}；手机号/证件号做掩码（保留首尾便于核对）；</li>
 *   <li>超长详情不截断成非法 JSON，而是替换为 {@code {"truncated":true,"originalLength":N}}；</li>
 *   <li>无法解析的入参按纯文本处理（包装成 JSON 字符串），**绝不抛异常**，避免因为详情格式问题丢审计。</li>
 * </ul>
 */
public final class AuditDetailSanitizer {

  /** 详情 JSON 长度上限（字符），与审计表 detail_json 的实际使用规模匹配。 */
  public static final int MAX_LENGTH = 8192;

  private static final String REDACTED = "[REDACTED]";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 命中即整体抹除的键（小写、去掉下划线后比较）。 */
  private static final Set<String> SECRET_KEYS = Set.of(
      "password", "passwd", "pwd", "oldpassword", "newpassword", "confirmpassword",
      "token", "accesstoken", "refreshtoken", "idtoken", "authtoken", "sessiontoken",
      "secret", "secretkey", "appsecret", "privatekey", "accesskey", "apikey", "clientsecret",
      "credential", "credentials", "authorization", "cookie", "signature", "verifycode", "captcha",
      "bankcard", "bankcardno", "cardno");

  /** 命中即做掩码的键（保留首尾）。 */
  private static final Set<String> MASK_KEYS = Set.of(
      "phone", "mobile", "phonenumber", "mobilenumber", "contactphone", "telephone",
      "idcard", "idcardno", "identityno", "identitynumber", "idnumber", "certno");

  private AuditDetailSanitizer() {}

  /**
   * 脱敏并规范化详情：入参可以是 JSON 对象字符串、JSON 数组字符串或普通文本；返回合法 JSON 字符串或 {@code null}。
   */
  public static String sanitize(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String sanitized;
    try {
      JsonNode node = MAPPER.readTree(raw);
      if (node == null || node.isMissingNode()) {
        sanitized = MAPPER.writeValueAsString(new TextNode(raw));
      } else {
        sanitized = MAPPER.writeValueAsString(scrub(node));
      }
    } catch (Exception parseFailure) {
      try {
        sanitized = MAPPER.writeValueAsString(new TextNode(raw));
      } catch (Exception serializationFailure) {
        return null;
      }
    }
    if (sanitized.length() > MAX_LENGTH) {
      return "{\"truncated\":true,\"originalLength\":" + sanitized.length() + "}";
    }
    return sanitized;
  }

  /** 递归清洗 JSON：对象按键名判定，数组逐元素清洗，标量原样保留。 */
  private static JsonNode scrub(JsonNode node) {
    if (node.isObject()) {
      ObjectNode result = MAPPER.createObjectNode();
      java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String key = entry.getKey();
        String normalized = normalize(key);
        if (SECRET_KEYS.contains(normalized)) {
          result.set(key, new TextNode(REDACTED));
        } else if (MASK_KEYS.contains(normalized)) {
          result.set(key, new TextNode(mask(text(entry.getValue()))));
        } else {
          result.set(key, scrub(entry.getValue()));
        }
      }
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = MAPPER.createArrayNode();
      List<JsonNode> children = new ArrayList<>();
      node.forEach(children::add);
      for (JsonNode child : children) {
        result.add(scrub(child));
      }
      return result;
    }
    return node;
  }

  private static String normalize(String key) {
    return key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() ? "" : node.asText();
  }

  /** 掩码：长度 >= 7 保留前 3 后 2，长度 >= 4 保留前 1 后 1，更短则整体替换。 */
  private static String mask(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    int length = value.length();
    if (length >= 7) {
      return value.substring(0, 3) + "****" + value.substring(length - 2);
    }
    if (length >= 4) {
      return value.charAt(0) + "***" + value.charAt(length - 1);
    }
    return REDACTED;
  }
}
