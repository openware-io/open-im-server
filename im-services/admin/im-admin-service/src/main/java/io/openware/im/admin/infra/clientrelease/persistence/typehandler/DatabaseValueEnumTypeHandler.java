package io.openware.im.admin.infra.clientrelease.persistence.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 受控枚举按显式数据库字面量存储，避免依赖 Java ordinal 或 MySQL ENUM 内部序号。 */
public abstract class DatabaseValueEnumTypeHandler<E extends Enum<E>> extends BaseTypeHandler<E> {
  @Override public void setNonNullParameter(PreparedStatement statement, int index, E parameter, JdbcType jdbcType) throws SQLException { statement.setString(index, databaseValue(parameter)); }
  @Override public E getNullableResult(ResultSet resultSet, String columnName) throws SQLException { String value = resultSet.getString(columnName); return value == null ? null : fromDatabaseValue(value); }
  @Override public E getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException { String value = resultSet.getString(columnIndex); return value == null ? null : fromDatabaseValue(value); }
  @Override public E getNullableResult(CallableStatement statement, int columnIndex) throws SQLException { String value = statement.getString(columnIndex); return value == null ? null : fromDatabaseValue(value); }
  protected abstract String databaseValue(E value);
  protected abstract E fromDatabaseValue(String value);
}
