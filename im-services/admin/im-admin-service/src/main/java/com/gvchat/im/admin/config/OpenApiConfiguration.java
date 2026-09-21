package com.gvchat.im.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 管理后台服务对外接口的 OpenAPI 元数据。 */
@Configuration
public class OpenApiConfiguration {
  @Bean
  public OpenAPI adminOpenApi() {
    return new OpenAPI().info(new Info()
        .title("IM 平台管理后台接口")
        .description("面向管理后台的用户、内容、运营、预约与系统配置接口。")
        .version("v1"));
  }
}
