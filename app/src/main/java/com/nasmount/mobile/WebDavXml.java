package com.nasmount.mobile;

import okhttp3.HttpUrl;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Android-compatible, streaming WebDAV PROPFIND response parser.
 *
 * Never configure JAXP/SAX vendor features on an Android device: several
 * platform XML factories reject them with ParserConfigurationException
 * before any actual DAV response is parsed. KXml pull parsing requires no
 * vendor-specific features and does not retrieve remote DTDs. Reject all
 * DTD declarations and unexpected entity references explicitly.
 */
final class WebDavXml {
    private WebDavXml() {}
    private static final long MAX_XML_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_ROWS = 100_000;

    private static final class Propstat {
        long size = -1;
        long modified;
        long created;
        boolean directory;
        boolean success = true;
    }
    private static final class ResponseEntry {
        String href;
        long size = -1;
        long modified;
        long created;
        boolean directory;
    }
    private static final class BoundedStream extends FilterInputStream {
        private long read;
        BoundedStream(InputStream in) { super(in); }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++read > MAX_XML_BYTES)
                throw new IOException("WebDAV 폴더 목록 크기가 허용 범위를 초과했습니다.");
            return value;
        }
        @Override public int read(byte[] b, int offset, int count) throws IOException {
            int value = in.read(b, offset, count);
            if (value > 0 && (read += value) > MAX_XML_BYTES)
                throw new IOException("WebDAV 폴더 목록 크기가 허용 범위를 초과했습니다.");
            return value;
        }
    }
    static List<Models.Entry> parse(InputStream body, HttpUrl requestedRoot,
                                     String logicalFolder) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new BoundedStream(body), "UTF-8");
        List<Models.Entry> out = new ArrayList<>();
        int responseCount = 0;
        ResponseEntry response = null;
        Propstat propstat = null;
        int responseDepth = -1;
        int propstatDepth = -1;
        int capturingDepth = -1;
        String capturingName = null;
        StringBuilder captured = new StringBuilder();
        boolean seenMultistatus = false;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
             event = parser.nextToken()) {
            if (event == XmlPullParser.DOCDECL)
                throw new IOException("WebDAV XML에서 DTD 선언은 허용되지 않습니다.");
            if (event == XmlPullParser.ENTITY_REF) {
                String name = parser.getName();
                if (name == null || !name.matches("amp|lt|gt|apos|quot"))
                    throw new IOException("WebDAV XML의 외부 개체 참조를 거부했습니다.");
                if (capturingName != null) {
                    String resolved = parser.getText();
                    if (resolved != null) captured.append(resolved);
                }
                continue;
            }
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                int depth = parser.getDepth();
                if ("multistatus".equals(name)) seenMultistatus = true;
                if ("response".equals(name) && response == null) {
                    response = new ResponseEntry();
                    responseDepth = depth;
                } else if (response != null) {
                    if ("propstat".equals(name) && propstat == null) {
                        propstat = new Propstat();
                        propstatDepth = depth;
                    } else if ("collection".equals(name) && propstat != null) {
                        propstat.directory = true;
                    } else if (("href".equals(name) && depth == responseDepth + 1) ||
                            (propstat != null && ("getcontentlength".equals(name) ||
                                    "getlastmodified".equals(name) ||
                                    "creationdate".equals(name) ||
                                    "status".equals(name)))) {
                        capturingName = name;
                        capturingDepth = depth;
                        captured.setLength(0);
                    }
                }
            } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                if (capturingName != null && parser.getText() != null)
                    captured.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG && response != null) {
                String name = parser.getName();
                int depth = parser.getDepth();
                if (capturingName != null && name.equals(capturingName) &&
                        depth == capturingDepth) {
                    String value = captured.toString().trim();
                    if ("href".equals(name)) response.href = value;
                    if ("getcontentlength".equals(name) && propstat != null) {
                        try { propstat.size = Long.parseLong(value); }
                        catch (NumberFormatException ignored) {}
                    }
                    if ("getlastmodified".equals(name) && propstat != null)
                        propstat.modified = WebDavTimestamp.parse(value);
                    if ("creationdate".equals(name) && propstat != null)
                        propstat.created = WebDavTimestamp.parse(value);
                    if ("status".equals(name) && propstat != null)
                        propstat.success = value.matches("(?s).*\\s(?:200|201|204|207)(?:\\s|$).*");
                    capturingName = null;
                    capturingDepth = -1;
                }
                if ("propstat".equals(name) && propstat != null &&
                        depth == propstatDepth) {
                    if (propstat.success) {
                        response.directory |= propstat.directory;
                        if (propstat.size >= 0) response.size = propstat.size;
                        if (propstat.modified > 0)
                            response.modified = propstat.modified;
                        if (propstat.created > 0)
                            response.created = propstat.created;
                    }
                    propstat = null;
                    propstatDepth = -1;
                }
                if ("response".equals(name) && depth == responseDepth) {
                    ++responseCount;
                    addEntry(out, response, requestedRoot, logicalFolder);
                    if (out.size() > MAX_ROWS)
                        throw new IOException("WebDAV 폴더 목록이 너무 깁니다.");
                    response = null;
                    responseDepth = -1;
                }
            }
        }
        if (!seenMultistatus)
            throw new IOException("서버가 WebDAV 폴더 목록 XML을 반환하지 않았습니다.");
        // A server reported entries but all hrefs failed path matching.
        // Do not misrepresent this protocol mismatch as an empty folder.
        if (responseCount > 1 && out.isEmpty())
            throw new IOException("WebDAV 하위 파일 경로를 해석하지 못했습니다. 서버 응답 경로를 확인해야 합니다.");
        return out;
    }

    private static void addEntry(List<Models.Entry> out, ResponseEntry response,
                                 HttpUrl requestedRoot, String logicalFolder) {
        if (response.href == null || response.href.isEmpty()) return;
        // Compare *decoded path segments*, not raw percent-encoded string
        // prefixes. Synology and other WebDAV servers may return literal
        // Hangul in href or use different %XX case/normalization than OkHttp.
        // On a nested folder the old raw startsWith silently discarded every
        // valid child even though the HTTP request itself succeeded.
        HttpUrl href = requestedRoot.resolve(response.href);
        if (href == null || (!href.scheme().equals("http") &&
                !href.scheme().equals("https"))) return;
        List<String> root = requestedRoot.pathSegments();
        List<String> parts = href.pathSegments();
        int rootCount = root.size();
        if (rootCount > 0 && root.get(rootCount - 1).isEmpty()) rootCount--;
        if (parts.size() != rootCount + 1) {
            // Trailing '/' on collection URLs is an empty final segment.
            if (parts.size() != rootCount + 2 ||
                    !parts.get(parts.size() - 1).isEmpty()) return;
        }
        for (int i = 0; i < rootCount; i++)
            if (!root.get(i).equals(parts.get(i))) return;
        String decoded = parts.get(rootCount);
        if (decoded.isEmpty()) return;
        try {
            String child = RemoteProvider.child(logicalFolder, decoded);
            Models.Entry entry = new Models.Entry(decoded, child, response.directory);
            entry.size = response.size;
            // Modification time takes priority over creation time when both
            // exist. Never invent a timestamp when the server omitted them.
            entry.modified = response.modified > 0 ?
                    response.modified : response.created;
            out.add(entry);
        } catch (IllegalArgumentException ignored) {
            // Malformed path or a malicious server-provided traversal entry.
        }
    }
}
