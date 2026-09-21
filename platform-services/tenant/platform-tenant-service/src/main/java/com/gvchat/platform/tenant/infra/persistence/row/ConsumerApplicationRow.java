package com.gvchat.platform.tenant.infra.persistence.row;

public class ConsumerApplicationRow {
    private String appId;
    private Long tenantId;
    private String tenantName;
    private Long organizationId;
    private String organizationName;
    private Long storeId;
    private String storeName;
    private String permissionsJson;
    private Integer authorizationVersion;

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getPermissionsJson() { return permissionsJson; }
    public void setPermissionsJson(String permissionsJson) { this.permissionsJson = permissionsJson; }
    public Integer getAuthorizationVersion() { return authorizationVersion; }
    public void setAuthorizationVersion(Integer authorizationVersion) { this.authorizationVersion = authorizationVersion; }
}
