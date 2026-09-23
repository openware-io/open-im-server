package io.openware.im.user.infra.persistence.device.typehandler;

import io.openware.common.enums.ClientPlatform;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public class ClientPlatformTypeHandler extends BaseTypeHandler<ClientPlatform> {
  @Override
  public void setNonNullParameter(PreparedStatement statement, int index, ClientPlatform parameter,
      JdbcType jdbcType) throws SQLException {
    statement.setString(index, parameter.getValue());
  }

  @Override
  public ClientPlatform getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
    return fromDatabaseValue(resultSet.getString(columnName));
  }

  @Override
  public ClientPlatform getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
    return fromDatabaseValue(resultSet.getString(columnIndex));
  }

  @Override
  public ClientPlatform getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
    return fromDatabaseValue(statement.getString(columnIndex));
  }

  private ClientPlatform fromDatabaseValue(String value) {
    return value == null ? null : ClientPlatform.fromValue(value);
  }
}
