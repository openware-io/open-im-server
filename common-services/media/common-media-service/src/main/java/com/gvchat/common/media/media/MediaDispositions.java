package com.gvchat.common.media.media;

import java.util.Locale;

/**
 * 公共媒体对象的 {@code Content-Disposition} 取值（`docs/standards/13_PLATFORM_MEDIA_CONVENTIONS.md` §2/§7）。
 *
 * <p>可直接渲染的类型（图片/音频/视频）必须落 {@code inline}：落成 {@code attachment} 时，
 * {@code <img>} 这类子资源渲染虽不受影响，但**直接打开/预览/新窗口查看/WebView 内查看**会被浏览器强制下载，
 * 表现为「上传成功、点开看不到图」，客户端也会把它当附件处理。
 *
 * <p>只有真正的附件/文档（PDF、文本、压缩包等）才用 {@code attachment}，避免误触发浏览器内联渲染。
 *
 * <p>上传时写入对象元数据；对**存量对象**（历史落成 attachment 的），由网关
 * {@code MediaContentDispositionFilter} 在响应上按 {@code Content-Type} 纠正为 inline。
 */
final class MediaDispositions {

    static final String INLINE = "inline";
    static final String ATTACHMENT = "attachment";

    private MediaDispositions() {
    }

    /** 按 MIME 主类型决定落库的 Content-Disposition。 */
    static String of(String contentType) {
        if (contentType == null) {
            return ATTACHMENT;
        }
        String type = contentType.trim().toLowerCase(Locale.ROOT);
        int slash = type.indexOf('/');
        String major = slash > 0 ? type.substring(0, slash) : type;
        return switch (major) {
            case "image", "audio", "video" -> INLINE;
            default -> ATTACHMENT;
        };
    }
}
