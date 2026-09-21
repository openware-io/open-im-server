package com.gvchat.im.message.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 消息服务对外接口的 OpenAPI 元数据。 */
@Configuration
public class OpenApiConfiguration {
  @Bean
  public OpenAPI messageOpenApi() {
    return new OpenAPI().info(new Info()
        .title("IM 平台消息服务接口")
        .description("面向移动端的消息查询、搜索、已读和删除接口。")
        .version("v1"));
  }
}
