package com.gvchat.im.user.application.account.command;

/**
 * 账号密码登录命令。
 *
 * <p>deviceId/deviceType/deviceName/ip 为多端登录会话记录所需的设备与来源信息，
 * 未提供时由应用层给出缺省值（不影响登录本身）。</p>
 */
public record LoginCommand(String username, String password, String deviceId, String deviceType, String deviceName,
    String ip) {
}
