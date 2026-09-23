package io.openware.im.user.infra.persistence.device.typehandler;

import io.openware.common.enums.PushProvider;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public class PushProviderTypeHandler extends BaseTypeHandler<PushProvider> {
  @Override
  public void setNonNullParameter(PreparedStatement statement, int index, PushProvider parameter,
      JdbcType jdbcType) throws SQLException {
    statement.setString(index, parameter.getValue());
  }

  @Override
  public PushProvider getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
    return fromDatabaseValue(resultSet.getString(columnName));
  }

  @Override
  public PushProvider getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
    return fromDatabaseValue(resultSet.getString(columnIndex));
  }

  @Override
  public PushProvider getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
    return fromDatabaseValue(statement.getString(columnIndex));
  }

  private PushProvider fromDatabaseValue(String value) {
    return value == null ? null : PushProvider.fromValue(value);
  }
}
