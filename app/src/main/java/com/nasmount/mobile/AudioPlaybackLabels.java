package com.nasmount.mobile;

import java.util.Locale;

/**
 * Human-readable labels for the CURRENTLY selected media track.
 * These labels do not infer a decoder from the media format.
 */
final class AudioPlaybackLabels {
    private AudioPlaybackLabels() {}

    static String codecName(String mimeType, String codecs) {
        String mime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        switch (mime) {
            case "audio/mp4a-latm":
            case "audio/aac": return "AAC";
            case "audio/eac3-joc": return "E-AC3 Atmos";
            case "audio/eac3": return "E-AC3";
            case "audio/ac3": return "AC3";
            case "audio/true-hd": return "TrueHD";
            case "audio/vnd.dts.hd": return "DTS-HD";
            case "audio/vnd.dts": return "DTS";
            case "audio/ac4": return "AC-4";
            case "audio/opus": return "Opus";
            case "audio/vorbis": return "Vorbis";
            case "audio/flac": return "FLAC";
            case "audio/alac": return "ALAC";
            case "audio/mpeg": return "MP3";
            case "audio/raw": return "PCM";
            case "audio/amr-nb": return "AMR-NB";
            case "audio/amr-wb": return "AMR-WB";
            case "audio/g711-alaw": return "G.711 A-law";
            case "audio/g711-mlaw": return "G.711 μ-law";
            default: break;
        }
        String identifier = codecs == null ? "" : codecs.toLowerCase(Locale.ROOT);
        if (identifier.startsWith("mp4a.")) return "AAC";
        if (identifier.startsWith("ec-3")) return "E-AC3";
        if (identifier.startsWith("ac-3")) return "AC3";
        if (identifier.startsWith("dts")) return "DTS";
        if (identifier.startsWith("opus")) return "Opus";
        if (identifier.startsWith("flac")) return "FLAC";
        if (!mime.isEmpty() && mime.startsWith("audio/"))
            return mimeType.substring(mimeType.indexOf('/') + 1).toUpperCase(Locale.ROOT);
        return "알 수 없음";
    }

    static String decoderKind(String decoderName) {
        if (decoderName == null || decoderName.trim().isEmpty()) return "확인 중";
        String value = decoderName.toLowerCase(Locale.ROOT);
        if (value.contains("ffmpeg")) return "FFmpeg (NextLib)";
        if (value.startsWith("c2.") || value.startsWith("omx.")
                || value.startsWith("c2android.") || value.contains("mediacodec"))
            return "Android MediaCodec";
        return "기타 디코더";
    }

    static String channelLabel(int channels) {
        switch (channels) {
            case 1: return "1채널 (모노)";
            case 2: return "2채널 (스테레오)";
            case 6: return "6채널 (5.1)";
            case 8: return "8채널 (7.1)";
            default: return channels > 0 ? channels + "채널" : "정보 없음";
        }
    }

    static String sampleRateLabel(int sampleRate) {
        if (sampleRate <= 0) return "정보 없음";
        if (sampleRate % 1000 == 0) return (sampleRate / 1000) + " kHz";
        return String.format(Locale.ROOT, "%.1f kHz", sampleRate / 1000f);
    }
}
