package io.openware.platform.tenant.domain.configuration.model;

/**
 * 配置解析结果：值 + 它来自哪一层作用域。
 *
 * <p>调用方需要作用域来源才能把它回显成"门店覆盖/业态默认/租户默认"（配置页要告诉运营
 * "这条营业时间是从哪继承来的"），因此来源是结果的一部分，不是调试信息。
 *
 * @param value  配置值
 * @param source 作用域来源：{@code STORE} / {@code BUSINESS} / {@code TENANT}
 */
public record TenantConfigValue(String value, Scope source) {

  /** 配置值的作用域层级。 */
  public enum Scope {
    /** 门店级覆盖。 */
    STORE,
    /** 业态默认。 */
    BUSINESS,
    /** 租户默认。 */
    TENANT
  }

  /** 门店级覆盖。 */
  public static TenantConfigValue store(String value) {
    return new TenantConfigValue(value, Scope.STORE);
  }

  /** 业态默认。 */
  public static TenantConfigValue business(String value) {
    return new TenantConfigValue(value, Scope.BUSINESS);
  }

  /** 租户默认。 */
  public static TenantConfigValue tenant(String value) {
    return new TenantConfigValue(value, Scope.TENANT);
  }
}
