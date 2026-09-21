package com.gvchat.im.user.api.authorization;

import java.util.List;

/** 批量查询用户设备公钥的请求（供会话服务在建立私密聊天时预填对端公钥）。 */
public record UserDeviceKeyQuery(List<Long> userIds) {
}
