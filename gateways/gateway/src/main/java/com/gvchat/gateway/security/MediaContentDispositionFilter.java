package com.gvchat.gateway.security;

import java.util.Locale;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 公共媒体读取的 {@code Content-Disposition} 纠正（`docs/standards/13_PLATFORM_MEDIA_CONVENTIONS.md` §2）。
 *
 * <p>对象存储里的**存量**对象是历史实现上传时落成 {@code Content-Disposition: attachment} 的：
 * {@code <img>} 这类子资源渲染不受影响，但**直接打开/预览/新窗口查看/WebView 内查看**会被浏览器强制下载，
 * 用户看到的是「上传成功、点开看不到图」。上传侧已改为图片/音视频落 {@code inline}
 * （{@code MediaDispositions}），但存量对象不会自动改写，因此这里在**响应**上按 {@code Content-Type} 纠正：
 * 只对 {@code /api/v1/media-public/**} 且响应类型为 image/audio/video 的请求把该头覆盖为 {@code inline}，
 * 其余（PDF、文本、压缩包等真正的附件）保持不动。
 *
 * <p>只改响应头、不缓冲响应体，因此对图片/视频流没有额外内存与延迟开销。
 */
public final class MediaContentDispositionFilter implements GlobalFilter, Ordered {

    private static final String PUBLIC_MEDIA_PREFIX = "/api/v1/media-public/";
    private static final String INLINE = "inline";
    /** 与会话过滤器同级偏后即可：本过滤器只依赖最终响应头，与鉴权/币种头互不影响。 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!isPublicMedia(path)) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            if (isInlineRenderable(response.getHeaders().getContentType())) {
                response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION, INLINE);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    /** 只处理公共媒体读取路由；旧的 {@code /media-public/**} 已废弃，不再匹配。 */
    static boolean isPublicMedia(String path) {
        return path != null && path.startsWith(PUBLIC_MEDIA_PREFIX);
    }

    /** 图片/音频/视频可内联渲染；其余（attachment/voucher 等）保持存储侧的头。 */
    static boolean isInlineRenderable(MediaType contentType) {
        if (contentType == null || contentType.getType() == null) {
            return false;
        }
        String major = contentType.getType().toLowerCase(Locale.ROOT);
        return "image".equals(major) || "audio".equals(major) || "video".equals(major);
    }
}
