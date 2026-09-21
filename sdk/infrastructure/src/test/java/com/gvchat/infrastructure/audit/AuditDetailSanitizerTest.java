package com.gvchat.infrastructure.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 审计详情脱敏与合法性：不得泄露凭据/隐私，且结果必须是合法 JSON（审计表 json 列强约束）。 */
class AuditDetailSanitizerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void removesCredentialsAndMasksPrivacyFields() throws Exception {
    String sanitized = AuditDetailSanitizer.sanitize(
        "{\"password\":\"p@ssw0rd\",\"refreshToken\":\"rt-1\",\"apiKey\":\"k-1\",\"secretKey\":\"s-1\","
            + "\"phone\":\"13800001111\",\"idCard\":\"310101199001011234\",\"amount\":\"12.30\"}");
    JsonNode node = MAPPER.readTree(sanitized);

    assertEquals("[REDACTED]", node.path("password").asText());
    assertEquals("[REDACTED]", node.path("refreshToken").asText());
    assertEquals("[REDACTED]", node.path("apiKey").asText());
    assertEquals("[REDACTED]", node.path("secretKey").asText());
    assertEquals("138****11", node.path("phone").asText());
    assertEquals("310****34", node.path("idCard").asText());
    assertEquals("12.30", node.path("amount").asText());
    assertFalse(sanitized.contains("p@ssw0rd"));
  }

  @Test
  void sanitizesNestedStructures() throws Exception {
    String sanitized = AuditDetailSanitizer.sanitize(
        "{\"before\":{\"mobile\":\"13900002222\"},\"items\":[{\"token\":\"t\"},{\"name\":\"可乐\"}]}");
    JsonNode node = MAPPER.readTree(sanitized);

    assertEquals("139****22", node.path("before").path("mobile").asText());
    assertEquals("[REDACTED]", node.path("items").get(0).path("token").asText());
    assertEquals("可乐", node.path("items").get(1).path("name").asText());
  }

  @Test
  void nonJsonDetailBecomesJsonString() throws Exception {
    String sanitized = AuditDetailSanitizer.sanitize("role-perm:12 permissionIds=[1,2]");
    assertTrue(MAPPER.readTree(sanitized).isTextual());
    assertEquals("role-perm:12 permissionIds=[1,2]", MAPPER.readTree(sanitized).asText());
  }

  @Test
  void blankDetailIsNullAndHugeDetailIsFoldedWithValidJson() throws Exception {
    assertNull(AuditDetailSanitizer.sanitize(null));
    assertNull(AuditDetailSanitizer.sanitize("  "));

    String huge = AuditDetailSanitizer.sanitize("{\"blob\":\"" + "x".repeat(AuditDetailSanitizer.MAX_LENGTH) + "\"}");
    JsonNode node = MAPPER.readTree(huge);
    assertTrue(node.path("truncated").asBoolean());
    assertTrue(node.path("originalLength").asLong() > AuditDetailSanitizer.MAX_LENGTH);
  }
}
