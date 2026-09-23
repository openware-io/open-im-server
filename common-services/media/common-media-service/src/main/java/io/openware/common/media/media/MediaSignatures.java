package io.openware.common.media.media;

final class MediaSignatures {
  private MediaSignatures() { }

  static boolean matches(String contentType, byte[] header) {
    if ("image/jpeg".equals(contentType)) return header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
    if ("image/png".equals(contentType)) return header.length >= 8 && (header[0] & 0xff) == 0x89 && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47;
    if ("image/webp".equals(contentType)) return header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F' && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
    if ("application/pdf".equals(contentType)) return header.length >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F' && header[4] == '-';
    if ("video/mp4".equals(contentType) || "audio/mp4".equals(contentType)) return header.length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
    if ("audio/wav".equals(contentType)) return header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F' && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E';
    if ("audio/ogg".equals(contentType)) return header.length >= 4 && header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S';
    if ("application/vnd.android.package-archive".equals(contentType) || "application/zip".equals(contentType)) {
      return header.length < 4 || (header[0] == 'P' && header[1] == 'K' && header[2] == 3 && header[3] == 4);
    }
    if ("application/octet-stream".equals(contentType)) return true;
    return "text/plain".equals(contentType);
  }
}
