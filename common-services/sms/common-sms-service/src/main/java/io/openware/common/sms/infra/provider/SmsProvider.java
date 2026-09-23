package io.openware.common.sms.infra.provider;

import java.util.Map;

/** 短信服务商 SPI：aliyun/tencent。当前为骨架，真实 HTTP 签名发送由各实现接入。 */
public interface SmsProvider {
    String provider();
    boolean enabled();
    String send(String signName, String templateCode, String phone, Map<String, String> params);
}