package com.gvchat.im.user.infra.persistence.device.typehandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

class DeviceTokenEnumTypeHandlerTest {
  @Test
  void mapsLowercaseProviderAndPlatformValues() throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("push_provider")).thenReturn("jpush");
    when(resultSet.getString("platform")).thenReturn("ios");

    assertEquals(PushProvider.JPUSH,
        new PushProviderTypeHandler().getNullableResult(resultSet, "push_provider"));
    assertEquals(ClientPlatform.IOS,
        new ClientPlatformTypeHandler().getNullableResult(resultSet, "platform"));
  }

  @Test
  void writesLowercaseProviderAndPlatformValues() throws Exception {
    PreparedStatement statement = mock(PreparedStatement.class);

    new PushProviderTypeHandler().setNonNullParameter(statement, 1, PushProvider.JPUSH, null);
    new ClientPlatformTypeHandler().setNonNullParameter(statement, 2, ClientPlatform.IOS, null);

    verify(statement).setString(1, "jpush");
    verify(statement).setString(2, "ios");
  }
}
