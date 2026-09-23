package io.openware.platform.admin.api.backend;

/** 统一登录入口的后台项。code: im=IM 后台, platform=平台运营后台, tenant=租户后台。 */
public record AdminBackend(String code, String name, String path, String icon) {
}
