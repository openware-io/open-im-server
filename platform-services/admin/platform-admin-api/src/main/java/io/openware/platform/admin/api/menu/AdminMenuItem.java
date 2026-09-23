package io.openware.platform.admin.api.menu;

import java.util.List;

/**
 * SaaS 后台菜单项（菜单 BFF 输出）。
 * scope: PLATFORM=平台运营后台, TENANT=租户后台。
 */
public record AdminMenuItem(Long id, Long parentId, String code, String name, String path,
                            String icon, String scope, List<AdminMenuItem> children) {
}
