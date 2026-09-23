package io.openware.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 公共媒体 {@code Content-Disposition} 纠正的判定：只有可直接渲染的类型才改写成 {@code inline}。
 *
 * <p>回归背景：对象存储里的存量对象是 {@code attachment}，直接打开/预览会被浏览器强制下载（表现为
 * 「上传成功但点开看不到图」）；响应侧只允许纠正 image/audio/video，PDF 等真正的附件必须保持不动。
 */
class MediaContentDispositionFilterTest {

    @Test
    void treatsImagesAudioAndVideoAsInlineRenderable() {
        assertThat(MediaContentDispositionFilter.isInlineRenderable(MediaType.IMAGE_PNG)).isTrue();
        assertThat(MediaContentDispositionFilter.isInlineRenderable(MediaType.IMAGE_JPEG)).isTrue();
        assertThat(MediaContentDispositionFilter.isInlineRenderable(MediaType.parseMediaType("image/webp"))).isTrue();
        assertThat(MediaContentDispositionFilter.isInlineRenderable(MediaType.parseMediaType("audio/mpeg"))).isTrue();
        assertThat(MediaContentDispositionFilter.isInlineRenderable(MediaType.parseMediaType("video/mp4"))).isTrue();
    }

    @Test
    void keepsAttachmentsAndUnknownTypesUntouched() {
        assertThat(MediaContentDispositionFilter.isInlineRenderable(MediaType.APPLICATION_PDF)).isFalse();
        assertThat(MediaContentDispositionFilter.isInlineRenderable(MediaType.TEXT_PLAIN)).isFalse();
        assertThat(MediaContentDispositionFilter.isInlineRenderable(MediaType.APPLICATION_OCTET_STREAM)).isFalse();
        assertThat(MediaContentDispositionFilter.isInlineRenderable(null)).isFalse();
    }

    @Test
    void publicMediaPathIsRecognisedByPrefix() {
        assertThat(MediaContentDispositionFilter.isPublicMedia("/api/v1/media-public/open-media-public/a/b.png")).isTrue();
        assertThat(MediaContentDispositionFilter.isPublicMedia("/api/v1/media/12/access")).isFalse();
        assertThat(MediaContentDispositionFilter.isPublicMedia("/media-public/open-media-public/a/b.png")).isFalse();
        assertThat(MediaContentDispositionFilter.isPublicMedia(null)).isFalse();
    }
}
