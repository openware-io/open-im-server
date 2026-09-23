package io.openware.im.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AdminManagementDtosTest {
  @Test
  void itemResponseStatusSerializesAsIntegerNotBoolean() throws Exception {
    AdminManagementDtos.ItemResponse response = new AdminManagementDtos.ItemResponse(
        1, 2, "外卖", "https://x", "", null, 1, false, "consumer", false, 0, null, null);
    ObjectMapper mapper = new ObjectMapper();
    JsonNode node = mapper.readTree(mapper.writeValueAsString(response));
    assertThat(node.get("status").isInt()).isTrue();
    assertThat(node.get("status").intValue()).isEqualTo(1);
    assertThat(node.get("status").isBoolean()).isFalse();
  }
}
