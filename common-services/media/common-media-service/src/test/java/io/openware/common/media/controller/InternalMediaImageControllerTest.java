package io.openware.common.media.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openware.common.media.media.MediaImageUploadService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 内部图片接口：查询参数与请求体映射到上传服务，响应结构与媒体服务公开契约一致。 */
class InternalMediaImageControllerTest {

  private final MediaImageUploadService images = mock(MediaImageUploadService.class);
  private final InternalMediaImageController controller = new InternalMediaImageController(images);

  @Test
  void mapsRequestIntoUploadServiceAndReturnsStructuredResponse() {
    when(images.upload(eq("saas"), eq("t100-s7"), eq("image/png"), eq(3L), any(InputStream.class)))
        .thenReturn(new MediaImageUploadService.UploadedImage(
            "/api/v1/media-public/open-media-public/saas/t100-s7/202609/abc.png", "saas/t100-s7/202609/abc.png",
            "open-media-public", "image/png", 3L));

    InternalMediaImageController.ImageUploadResponse response =
        controller.upload("saas", "t100-s7", "image/png", "png".getBytes(StandardCharsets.UTF_8));

    assertThat(response.url()).isEqualTo("/api/v1/media-public/open-media-public/saas/t100-s7/202609/abc.png");
    assertThat(response.objectKey()).isEqualTo("saas/t100-s7/202609/abc.png");
    assertThat(response.bucket()).isEqualTo("open-media-public");
    assertThat(response.contentType()).isEqualTo("image/png");
    assertThat(response.size()).isEqualTo(3L);
  }

  @Test
  void acceptsRawImageBodyOverHttpAndReturnsPublicUrl() throws Exception {
    when(images.upload(eq("saas"), eq("t100"), eq("image/png"), eq(3L), any(InputStream.class)))
        .thenReturn(new MediaImageUploadService.UploadedImage(
            "/api/v1/media-public/open-media-public/saas/t100/202609/abc.png", "saas/t100/202609/abc.png",
            "open-media-public", "image/png", 3L));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    mvc.perform(post("/internal/media/images").queryParam("biz", "saas").queryParam("scope", "t100")
            .contentType(MediaType.IMAGE_PNG).content("png".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("/api/v1/media-public/open-media-public/saas/t100/202609/abc.png"))
        .andExpect(jsonPath("$.objectKey").value("saas/t100/202609/abc.png"))
        .andExpect(jsonPath("$.bucket").value("open-media-public"))
        .andExpect(jsonPath("$.contentType").value("image/png"))
        .andExpect(jsonPath("$.size").value(3));
  }

  @Test
  void forwardsEmptyBodyAsZeroSize() {
    when(images.upload(eq(null), eq(null), eq("image/png"), eq(0L), any(InputStream.class)))
        .thenReturn(new MediaImageUploadService.UploadedImage(null, null, null, null, 0L));

    controller.upload(null, null, "image/png", null);

    verify(images).upload(eq(null), eq(null), eq("image/png"), eq(0L), any(InputStream.class));
  }
}
