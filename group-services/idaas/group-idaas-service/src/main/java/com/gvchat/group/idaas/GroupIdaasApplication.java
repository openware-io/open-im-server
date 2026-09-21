package com.gvchat.group.idaas;

import com.gvchat.infrastructure.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 集团 IDaaS 服务：面向「后台统一登录」的集团域认证 / 目录 / 接入方授权。
 *
 * <p>按仓库既有规范（im-user / im-admin / identity）仅选择性导入基础设施的
 * {@link JwtProperties}（复用 jwt.secret 配置与校验），而不是整体扫描
 * com.gvchat.infrastructure，以免误引入 Redis / RocketMQ / Web Security 等
 * 本服务当前不使用的运行时依赖。</p>
 */
@SpringBootApplication(scanBasePackages = "com.gvchat.group")
@Import({JwtProperties.class})
public class GroupIdaasApplication {
  public static void main(String[] args) {
    SpringApplication.run(GroupIdaasApplication.class, args);
  }
}
