package com.nasmount.mobile;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class NasBulkRevokeTest {
    private NasShareApi.Link link(String id) {
        return new NasShareApi.Link(id, "/video/" + id + ".mp4",
                "https://example.com/share/" + id, 0);
    }

    @Test public void failuresDoNotPreventLaterLinkRevocation() {
        List<NasShareApi.Link> targets = Arrays.asList(link("one"), link("two"), link("three"));
        List<String> calls = new ArrayList<>();
        NasShareApi.RevokeReport report = NasShareApi.revokeEach(targets, entry -> {
            calls.add(entry.id);
            if ("two".equals(entry.id)) throw new IOException("permission denied");
        });
        assertEquals(Arrays.asList("one", "two", "three"), calls);
        assertEquals(3, report.requested);
        assertEquals(2, report.succeeded);
        assertEquals(1, report.failed());
        assertEquals(1, report.failures.size());
        assertTrue(report.failures.get(0).contains("two.mp4"));
    }

    @Test public void allSuccessAndAllFailuresReportAccurately() {
        NasShareApi.RevokeReport success = NasShareApi.revokeEach(
                Arrays.asList(link("a"), link("b")), entry -> {});
        assertEquals(2, success.succeeded);
        assertEquals(0, success.failed());
        NasShareApi.RevokeReport failure = NasShareApi.revokeEach(
                Arrays.asList(link("a"), link("b")), entry -> {
                    throw new IOException("denied");
                });
        assertEquals(0, failure.succeeded);
        assertEquals(2, failure.failed());
    }

    @Test public void emptyBatchIsNotReportedAsFailure() {
        NasShareApi.RevokeReport report = NasShareApi.revokeEach(
                Collections.emptyList(), entry -> fail("should not execute"));
        assertEquals(0, report.requested);
        assertEquals(0, report.succeeded);
        assertEquals(0, report.failed());
    }
}
