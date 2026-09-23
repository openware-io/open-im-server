package io.openware.common.media.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 上传时落库的 {@code Content-Disposition}：可直接渲染的类型必须 inline，附件才 attachment。
 *
 * <p>回归背景：适配器曾对所有对象无条件落 {@code attachment}，导致直接打开/预览/WebView 查看被强制下载
 * （用户表现为「上传成功但看不到图」）。
 */
class MediaDispositionsTest {

    @Test
    void renderableTypesAreInline() {
        assertThat(MediaDispositions.of("image/png")).isEqualTo("inline");
        assertThat(MediaDispositions.of("image/jpeg")).isEqualTo("inline");
        assertThat(MediaDispositions.of("IMAGE/WEBP")).isEqualTo("inline");
        assertThat(MediaDispositions.of(" audio/mpeg ")).isEqualTo("inline");
        assertThat(MediaDispositions.of("video/mp4")).isEqualTo("inline");
    }

    @Test
    void nonRenderableTypesAreAttachments() {
        assertThat(MediaDispositions.of("application/pdf")).isEqualTo("attachment");
        assertThat(MediaDispositions.of("text/plain")).isEqualTo("attachment");
        assertThat(MediaDispositions.of("application/zip")).isEqualTo("attachment");
        assertThat(MediaDispositions.of("application/octet-stream")).isEqualTo("attachment");
    }

    @Test
    void missingContentTypeFallsBackToAttachment() {
        assertThat(MediaDispositions.of(null)).isEqualTo("attachment");
        assertThat(MediaDispositions.of("")).isEqualTo("attachment");
        assertThat(MediaDispositions.of("  ")).isEqualTo("attachment");
    }
}
