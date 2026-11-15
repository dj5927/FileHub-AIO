package com.nasmount.mobile;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parses standard WebDAV getlastmodified (HTTP-date) and creationdate. */
final class WebDavTimestamp {
    private WebDavTimestamp() {}

    static long parse(String raw) {
        if (raw == null) return 0L;
        String date = raw.trim();
        if (date.isEmpty() || date.length() > 128) return 0L;
        try {
            return positiveMillis(ZonedDateTime.parse(
                    date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException ignored) {
            // WebDAV creationdate is generally an ISO-8601 timestamp.
        }
        try {
            return positiveMillis(Instant.parse(date));
        } catch (DateTimeParseException ignored) {
            // Some servers include a numeric timezone offset.
        }
        try {
            return positiveMillis(OffsetDateTime.parse(date).toInstant());
        } catch (DateTimeParseException ignored) {
            return 0L;
        }
    }

    private static long positiveMillis(Instant timestamp) {
        try {
            long millis = timestamp.toEpochMilli();
            return millis > 0 ? millis : 0L;
        } catch (ArithmeticException outOfRange) {
            return 0L;
        }
    }
}
