package com.nasmount.mobile;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts legacy SAMI (.smi/.sami) captions to UTF-8 SubRip text.
 * This is an in-memory conversion: the NAS/user's original file is not
 * modified. Android/Media3 reads a temporary .srt in the app cache.
 */
final class SamiSubtitleConverter {
    private static final Pattern SYNC = Pattern.compile(
            "(?is)<sync\\b[^>]*?\\bstart\\s*=\\s*['\"]?(-?\\d+)[^>]*>");
    private static final Pattern PARAGRAPH = Pattern.compile(
            "(?is)<p\\b([^>]*)>(.*?)(?=<p\\b|$)");
    private static final Pattern CLASS = Pattern.compile(
            "(?i)\\bclass\\s*=\\s*['\"]?([\\w-]+)");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern NUM_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");

    private SamiSubtitleConverter() {}

    static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            throw new IllegalArgumentException("빈 자막 파일입니다.");
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF &&
                (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF)
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFF &&
                (bytes[1] & 0xff) == 0xFE)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFE &&
                (bytes[1] & 0xff) == 0xFF)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException legacyKorean) {
            Charset charset;
            try {
                charset = Charset.forName("MS949");
            } catch (Exception unavailable) {
                charset = Charset.forName("EUC-KR");
            }
            return new String(bytes, charset);
        }
    }

    static String toSrt(byte[] original) {
        return toSrtText(decode(original));
    }

    static String toSrtText(String sami) {
        if (sami == null || !sami.toLowerCase(Locale.ROOT).contains("<sami"))
            throw new IllegalArgumentException("SMI/SAMI 형식이 아닙니다.");
        Matcher matcher = SYNC.matcher(sami);
        List<Long> times = new ArrayList<>();
        List<String> captions = new ArrayList<>();
        long last = -1;
        while (matcher.find()) {
            long timestamp;
            try { timestamp = Long.parseLong(matcher.group(1)); }
            catch (NumberFormatException invalidTime) { continue; }
            if (timestamp < 0) timestamp = 0;
            if (!times.isEmpty()) {
                String between = sami.substring((int) last, matcher.start());
                captions.add(captionText(between));
            }
            times.add(timestamp);
            last = matcher.end();
        }
        if (times.isEmpty())
            throw new IllegalArgumentException("SMI에서 자막 시간 정보를 찾지 못했습니다.");
        // The last caption is commonly a watermark; let it last three seconds.
        captions.add(captionText(sami.substring((int) last)
                .replaceAll("(?is)</body>.*", "")));
        StringBuilder out = new StringBuilder(sami.length() + 1000);
        int cue = 0;
        for (int i = 0; i < times.size(); i++) {
            String text = captions.get(i);
            if (text.isEmpty()) continue;
            long from = times.get(i);
            long to = i + 1 < times.size() ?
                    times.get(i + 1) : from + 3000;
            if (to <= from) continue;
            out.append(++cue).append('\n');
            out.append(time(from)).append(" --> ").append(time(to))
                    .append('\n').append(text).append("\n\n");
        }
        if (cue == 0)
            throw new IllegalArgumentException("SMI에서 표시 가능한 자막을 찾지 못했습니다.");
        return out.toString();
    }

    private static String captionText(String segment) {
        Matcher matcher = PARAGRAPH.matcher(segment);
        String first = "";
        while (matcher.find()) {
            String cleaned = clean(matcher.group(2));
            if (first.isEmpty()) first = cleaned;
            Matcher type = CLASS.matcher(matcher.group(1));
            if (type.find()) {
                String name = type.group(1).toLowerCase(Locale.ROOT);
                if (name.startsWith("kr") || name.startsWith("ko"))
                    return cleaned;
            }
        }
        return first.isEmpty() ? clean(segment) : first;
    }

    private static String clean(String input) {
        String withoutComments = COMMENTS.matcher(input).replaceAll("");
        String lines = BR.matcher(withoutComments).replaceAll("\n");
        lines = TAGS.matcher(lines).replaceAll("");
        lines = lines.replace("&nbsp;", " ").replace("&NBSP;", " ")
                .replace("&#160;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
        Matcher entity = NUM_ENTITY.matcher(lines);
        StringBuffer result = new StringBuffer();
        while (entity.find()) {
            String digits = entity.group(1);
            int code = digits.startsWith("x") ?
                    Integer.parseInt(digits.substring(1), 16) :
                    Integer.parseInt(digits);
            String replacement = Character.isValidCodePoint(code) ?
                    new String(Character.toChars(code)) : "";
            entity.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        entity.appendTail(result);
        String normalized = result.toString().replace('\r', '\n')
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n").trim();
        return normalized.replaceAll("(?i)^(?:\\s|\\u00a0)+$", "");
    }

    private static String time(long millis) {
        long hours = millis / 3600000L;
        long minutes = (millis / 60000L) % 60L;
        long seconds = (millis / 1000L) % 60L;
        long ms = millis % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                hours, minutes, seconds, ms);
    }
}
