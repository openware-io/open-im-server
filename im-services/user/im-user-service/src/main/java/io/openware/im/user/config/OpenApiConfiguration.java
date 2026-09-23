package io.openware.im.user.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 用户服务对外接口的 OpenAPI 元数据。 */
@Configuration
public class OpenApiConfiguration {
  @Bean
  public OpenAPI userOpenApi() {
    return new OpenAPI().info(new Info()
        .title("IM 平台用户服务接口")
        .description("面向移动端的认证、用户资料、好友、推送设备与贴纸接口。")
        .version("v1"));
  }
}
