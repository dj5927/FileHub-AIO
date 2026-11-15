package com.nasmount.mobile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public final class TextDocumentCodecTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void utf8KoreanAndCrLfKeepExactBytes() throws Exception {
        byte[] original = "한글 문서\r\n두 번째 줄\r\n".getBytes(StandardCharsets.UTF_8);
        TextDocumentCodec.Document result = TextDocumentCodec.decode(original);
        assertEquals("한글 문서\r\n두 번째 줄\r\n", result.content);
        assertEquals("UTF-8", result.charset.name());
        assertArrayEquals(original, TextDocumentCodec.encode(result, result.content));
    }

    @Test public void utf8BomIsPreservedOnSave() throws Exception {
        byte[] data = {(byte)0xEF, (byte)0xBB, (byte)0xBF, 'A', '\n'};
        TextDocumentCodec.Document doc = TextDocumentCodec.decode(data);
        assertTrue(doc.encodingLabel().contains("BOM"));
        assertArrayEquals(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF,
                'B','\n'}, TextDocumentCodec.encode(doc, "B\n"));
    }

    @Test public void utf16LeAndBeBomStayIntact() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            byte[] bom = le ? new byte[]{(byte)0xFF,(byte)0xFE} :
                    new byte[]{(byte)0xFE,(byte)0xFF};
            Charset charset = le ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
            byte[] payload = "서울\r\n".getBytes(charset);
            byte[] source = new byte[bom.length + payload.length];
            System.arraycopy(bom,0,source,0,2);
            System.arraycopy(payload,0,source,2,payload.length);
            TextDocumentCodec.Document document = TextDocumentCodec.decode(source);
            assertEquals("서울\r\n", document.content);
            assertArrayEquals(source, TextDocumentCodec.encode(document, document.content));
        }
    }

    @Test public void legacyKoreanIsNotSilentlyReencodedToUtf8() throws Exception {
        byte[] legacy = "한글이 있는 문서".getBytes(Charset.forName("MS949"));
        TextDocumentCodec.Document document = TextDocumentCodec.decode(legacy);
        assertTrue(document.charset.name().equalsIgnoreCase("MS949") ||
                document.charset.name().toUpperCase().contains("949"));
        assertArrayEquals(legacy, TextDocumentCodec.encode(document, document.content));
    }

    @Test public void unmappableEmojiInLegacyTextIsRejectedNotLost() throws Exception {
        byte[] legacy = "한글".getBytes(Charset.forName("MS949"));
        TextDocumentCodec.Document document = TextDocumentCodec.decode(legacy);
        assertThrows(java.io.IOException.class, () ->
                TextDocumentCodec.encode(document, document.content + "🙂"));
    }

    @Test public void rejectBinaryNullAndControlBytes() {
        assertThrows(java.io.IOException.class, () ->
                TextDocumentCodec.decode(new byte[]{'A',0,'B'}));
        assertThrows(java.io.IOException.class, () ->
                TextDocumentCodec.decode(new byte[]{2,3,4,5,6,7,8}));
    }

    @Test public void boundedReadRejectsOversizedFile() throws Exception {
        byte[] buffer = new byte[TextDocumentCodec.MAX_BYTES+1];
        assertThrows(java.io.IOException.class, () ->
                TextDocumentCodec.readBounded(new ByteArrayInputStream(buffer)));
    }

    @Test public void atomicWriteReplacesOnlyTargetAndLeavesNoTemp() throws Exception {
        File folder = tmp.newFolder("documents");
        File document = new File(folder, "memo.txt");
        Files.write(document.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        TextDocumentCodec.atomicWrite(document,
                "new 한글".getBytes(StandardCharsets.UTF_8));
        assertEquals("new 한글",
                new String(Files.readAllBytes(document.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, folder.listFiles().length);
    }
}
