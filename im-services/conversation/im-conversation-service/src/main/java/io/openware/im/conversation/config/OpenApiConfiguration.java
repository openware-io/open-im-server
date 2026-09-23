package io.openware.im.conversation.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 会话服务对外接口的 OpenAPI 元数据。 */
@Configuration
public class OpenApiConfiguration {
  @Bean
  public OpenAPI conversationOpenApi() {
    return new OpenAPI().info(new Info()
        .title("IM 平台会话服务接口")
        .description("面向移动端的群组、实时音视频与文件上传接口。")
        .version("v1"));
  }
}
