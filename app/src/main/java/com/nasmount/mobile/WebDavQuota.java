package com.nasmount.mobile;

import java.io.StringReader;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** RFC 4331 capacity response; null means unknown, never zero free space. */
final class WebDavQuota {
    final long total;
    final long free;
    WebDavQuota(long total, long free) {
        this.total = total;
        this.free = free;
    }

    static WebDavQuota parse(String xml) throws Exception {
        if (xml == null || xml.length() > 65_536)
            throw new IllegalArgumentException("WebDAV 용량 응답 크기 초과");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        NodeList records = f.newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)))
                .getElementsByTagNameNS("DAV:", "propstat");
        for (int i = 0; i < records.getLength(); i++) {
            Element group = (Element) records.item(i);
            String status = text(group, "status");
            if (!status.contains(" 200 ")) continue;
            NodeList props = group.getElementsByTagNameNS("DAV:", "prop");
            if (props.getLength() == 0) continue;
            Element prop = (Element) props.item(0);
            try {
                long used = Long.parseLong(text(prop, "quota-used-bytes").trim());
                long available = Long.parseLong(
                        text(prop, "quota-available-bytes").trim());
                if (used >= 0 && available >= 0 &&
                        available <= Long.MAX_VALUE - used &&
                        used + available > 0)
                    return new WebDavQuota(used + available, available);
            } catch (NumberFormatException ignored) { /* Unsupported. */ }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagNameNS("DAV:", name);
        Node element = nodes.getLength() == 0 ? null : nodes.item(0);
        return element == null ? "" : element.getTextContent();
    }

    static String summary(WebDavQuota quota) {
        return "서버 용량 " + size(quota.total) + " · 사용 가능 " +
                size(quota.free);
    }

    private static String size(long size) {
        double n = size;
        String unit = "B";
        for (String next : new String[]{"KB", "MB", "GB", "TB", "PB", "EB"}) {
            if (n < 1024) break;
            n /= 1024;
            unit = next;
        }
        return String.format(Locale.KOREA, "%.2f %s", n, unit);
    }
}
