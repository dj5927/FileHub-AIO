package com.nasmount.mobile;

import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.Assert.*;

public class EpubArchiveTest {
    @Test public void readingWeightCountsVisibleBodyNotXhtmlCssOrMetadata(){
        String html="<html><head><title>매우 긴 제목</title>"+
                "<style>body{background:black;}</style></head>"+
                "<body><p>가나다라 마바사</p><p>아자차카</p></body></html>";
        assertEquals("가나다라 마바사 아자차카".length(),
                EpubArchive.visibleTextLength(html));
    }
    @Test public void spineIsReadingOrderNotManifestOrder()throws Exception{
        File epub=File.createTempFile("nas-epub-unit-", ".epub");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(epub))){
            add(zip,"META-INF/container.xml",
                    "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'>"+
                    "<rootfiles><rootfile full-path='OEBPS/package.opf'/></rootfiles></container>");
            add(zip,"OEBPS/package.opf",
                    "<package xmlns='http://www.idpf.org/2007/opf'>"+
                    "<metadata><title xmlns='http://purl.org/dc/elements/1.1/'>테스트 소설</title></metadata>"+
                    "<manifest><item id='b' href='b.xhtml' media-type='application/xhtml+xml'/>"+
                    "<item id='a' href='a.xhtml' media-type='application/xhtml+xml'/></manifest>"+
                    "<spine><itemref idref='a'/><itemref idref='b'/></spine></package>");
            add(zip,"OEBPS/a.xhtml","<html><body>첫 장</body></html>");
            add(zip,"OEBPS/b.xhtml","<html><body>다음 장</body></html>");
        }
        try(EpubArchive read=new EpubArchive(epub)){
            assertEquals("테스트 소설",read.title);
            assertEquals(2,read.chapters.size());
            assertEquals("OEBPS/a.xhtml",read.chapters.get(0).path);
            assertEquals("OEBPS/b.xhtml",read.chapters.get(1).path);
            assertTrue(new String(read.chapter(0),"UTF-8").contains("첫 장"));
            // The progress source is actual readable text, not OPF spine
            // count or the byte length of HTML metadata/markup.
            assertArrayEquals(new long[]{3L,4L},read.readingWeights());
        }
    }
    @Test public void resourcePathCannotEscapeZipRoot()throws Exception {
        assertEquals("OEBPS/img/1.png",
                EpubArchive.normalized("OEBPS/Text/ch1.xhtml","../img/1.png"));
        try{
            EpubArchive.normalized("OEBPS/ch.xhtml","../../outside");
            fail("traversal should be blocked");
        }catch(java.io.IOException expected){}
    }
    @Test public void unsupportedAndroidXmlFeatureIsOptional()throws Exception {
        DocumentBuilderFactory parser=DocumentBuilderFactory.newInstance();
        assertFalse(EpubArchive.optionalFeature(parser,
                "urn:nasmount:unsupported-android-epub-feature",true));
        // EPUB OCF still parses even when optional Xerces features are absent.
        assertEquals("container",EpubArchive.parseXml(
                "<container><rootfile full-path='book.opf'/></container>"
                        .getBytes(StandardCharsets.UTF_8))
                .getDocumentElement().getTagName());
    }
    @Test public void externalEntityIsRejectedInUtf8AndUtf16()throws Exception {
        String malicious="<?xml version='1.0'?><!DOCTYPE container ["+
                "<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"+
                "<container>&xxe;</container>";
        for(java.nio.charset.Charset charset:new java.nio.charset.Charset[]{
                StandardCharsets.UTF_8,StandardCharsets.UTF_16LE,
                StandardCharsets.UTF_16BE}){
            try {
                EpubArchive.parseXml(malicious.getBytes(charset));
                fail("DOCTYPE must be rejected: "+charset);
            }catch(java.io.IOException expected){
                assertTrue(expected.getMessage().contains("DTD/ENTITY"));
            }
        }
    }
    @Test public void commonOpfNamespacesAndMetadataAreReadable()throws Exception {
        String xml="<?xml version='1.0' encoding='utf-8'?>"+
                "<opf:package xmlns:opf='http://www.idpf.org/2007/opf' "+
                "xmlns:dc='http://purl.org/dc/elements/1.1/'>"+
                "<opf:metadata><dc:title>드래곤 라자</dc:title></opf:metadata>"+
                "<opf:manifest/><opf:spine/></opf:package>";
        assertEquals("드래곤 라자",EpubArchive.parseXml(
                xml.getBytes(StandardCharsets.UTF_8))
                .getElementsByTagNameNS("*","title").item(0).getTextContent());
    }
    private static void add(ZipOutputStream zip,String name,String text)throws Exception{
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes("UTF-8"));
        zip.closeEntry();
    }
}
