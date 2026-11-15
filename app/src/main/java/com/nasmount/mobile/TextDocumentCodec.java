package com.nasmount.mobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Bounded text documents with preserved encoding and BOM. */
final class TextDocumentCodec {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final class Document {
        final String content;
        final Charset charset;
        final byte[] bom;
        Document(String content, Charset charset, byte[] bom) {
            this.content = content;
            this.charset = charset;
            this.bom = bom;
        }
        String encodingLabel() {
            return charset.displayName() + (bom.length > 0 ? " · BOM" : "");
        }
    }
    private TextDocumentCodec() {}

    static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            if (result.size() + (long) count > MAX_BYTES)
                throw new IOException("문서가 4MB보다 커서 편집할 수 없습니다.");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    static Document decode(byte[] data) throws IOException {
        if (data.length > MAX_BYTES)
            throw new IOException("문서가 4MB보다 커서 편집할 수 없습니다.");
        Charset charset = StandardCharsets.UTF_8;
        byte[] bom = new byte[0];
        int start = 0;
        if (startsWith(data, 0xEF, 0xBB, 0xBF)) {
            bom = new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF};
            start = 3;
        } else if (startsWith(data, 0xFF, 0xFE)) {
            charset = StandardCharsets.UTF_16LE;
            bom = new byte[]{(byte)0xFF, (byte)0xFE};
            start = 2;
        } else if (startsWith(data, 0xFE, 0xFF)) {
            charset = StandardCharsets.UTF_16BE;
            bom = new byte[]{(byte)0xFE, (byte)0xFF};
            start = 2;
        }
        String content;
        try {
            content = strictDecode(data, start, charset);
        } catch (CharacterCodingException badUtf8) {
            if (start != 0)
                throw new IOException("유효하지 않은 유니코드 문서입니다.", badUtf8);
            try {
                charset = Charset.forName("MS949");
            } catch (Exception unsupported) {
                charset = Charset.forName("EUC-KR");
            }
            try {
                content = strictDecode(data, 0, charset);
            } catch (CharacterCodingException invalid) {
                throw new IOException("지원하지 않는 문자 인코딩 또는 바이너리 파일입니다.",
                        invalid);
            }
        }
        int controls = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\u0000') throw new IOException("바이너리 파일은 편집할 수 없습니다.");
            if (c < 0x20 && c != '\r' && c != '\n' && c != '\t' && c != '\f')
                controls++;
        }
        if (controls > Math.max(1, content.length() / 100))
            throw new IOException("바이너리 파일로 보이는 내용은 편집하지 않습니다.");
        return new Document(content, charset, bom);
    }

    private static String strictDecode(byte[] data, int offset, Charset charset)
            throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, offset, data.length - offset)).toString();
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++)
            if ((data[i] & 255) != prefix[i]) return false;
        return true;
    }

    static byte[] encode(Document original, String newContent) throws IOException {
        try {
            ByteBuffer encodedBuffer = original.charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(newContent));
            byte[] encoded = new byte[encodedBuffer.remaining()];
            encodedBuffer.get(encoded);
            if (encoded.length + original.bom.length > MAX_BYTES)
                throw new IOException("편집한 문서가 4MB를 초과해 저장할 수 없습니다.");
            byte[] result = new byte[original.bom.length + encoded.length];
            System.arraycopy(original.bom, 0, result, 0, original.bom.length);
            System.arraycopy(encoded, 0, result, original.bom.length, encoded.length);
            return result;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("문서를 원래 인코딩으로 저장할 수 없습니다.", e);
        }
    }

    static void atomicWrite(File target, byte[] content) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory())
            throw new IOException("대상 폴더를 찾을 수 없습니다.");
        File staging = File.createTempFile(".nasmount-text-", ".tmp", parent);
        boolean moved = false;
        try {
            try (FileOutputStream out = new FileOutputStream(staging)) {
                out.write(content);
                out.getFD().sync();
            }
            try {
                Files.move(staging.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved && staging.exists()) staging.delete();
        }
    }
}
