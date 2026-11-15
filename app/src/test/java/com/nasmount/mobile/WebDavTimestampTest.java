package com.nasmount.mobile;

import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public final class WebDavTimestampTest {
    @Test public void readsRfc1123WebDavLastModified() {
        assertEquals(Instant.parse("2026-11-12T03:05:07Z").toEpochMilli(),
                WebDavTimestamp.parse("Thu, 12 Nov 2026 03:05:07 GMT"));
    }

    @Test public void supportsIso8601CreationDate() {
        assertEquals(Instant.parse("2025-07-19T09:30:00Z").toEpochMilli(),
                WebDavTimestamp.parse("2025-07-19T09:30:00Z"));
    }

    @Test public void convertsIso8601OffsetsToUtc() {
        assertEquals(Instant.parse("2026-11-12T03:00:00Z").toEpochMilli(),
                WebDavTimestamp.parse("2026-11-12T12:00:00+09:00"));
    }

    @Test public void blankMalformedAndOversizedDatesStayUnknown() {
        assertEquals(0, WebDavTimestamp.parse(null));
        assertEquals(0, WebDavTimestamp.parse(" "));
        assertEquals(0, WebDavTimestamp.parse("not-a-date"));
        assertEquals(0, WebDavTimestamp.parse(new String(new char[129])
                .replace('\0', 'x')));
    }

    @Test public void timestampsBeforeEpochAreNotPresentedAsRealFileDates() {
        assertEquals(0, WebDavTimestamp.parse("Fri, 01 Jan 1960 00:00:00 GMT"));
    }
}
