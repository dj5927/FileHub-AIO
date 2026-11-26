package com.nasmount.mobile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class OfficeSaveIntegrityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File payload(String name, byte[] data) throws IOException {
        File f = temp.newFile(name);
        Files.write(f.toPath(), data);
        return f;
    }

    private void rejects(Action action) throws Exception {
        try { action.run(); fail("invalid office payload was accepted"); }
        catch (IOException expected) { }
    }
    interface Action { void run() throws Exception; }

    @Test public void emptySavePreservesOriginal() throws Exception {
        byte[] original = {1, 2, 3, 4};
        File dest = payload("book.xlsx", original);
        File empty = payload("empty.xlsx", new byte[0]);
        rejects(() -> OfficeSaveIntegrity.writeLocal(empty, dest, 100));
        assertArrayEquals(original, Files.readAllBytes(dest.toPath()));
        rejects(() -> OfficeSaveIntegrity.snapshot(empty, temp.getRoot(), 100));
    }

    @Test public void readbackRejectsZeroShortLongAndWrongBytes() throws Exception {
        byte[] data = {1, 2, 3};
        byte[] hash = OfficeSaveIntegrity.digest(payload("edit.xlsx", data));
        for (byte[] bad : new byte[][] {{}, {1, 2}, {1, 2, 3, 4}, {1, 2, 4}}) {
            rejects(() -> OfficeSaveIntegrity.verify(new ByteArrayInputStream(bad), 3, hash));
        }
        OfficeSaveIntegrity.verify(new ByteArrayInputStream(data), 3, hash);
    }

    @Test public void snapshotIsIndependentOfEditor() throws Exception {
        byte[] data = {4, 5, 6};
        File editor = payload("editor.xlsx", data);
        File snapshot = OfficeSaveIntegrity.snapshot(editor, temp.getRoot(), 100);
        Files.write(editor.toPath(), new byte[0]);
        assertArrayEquals(data, Files.readAllBytes(snapshot.toPath()));
    }

    @Test public void shortCopyIsRejected() throws Exception {
        rejects(() -> OfficeSaveIntegrity.copyExact(new ByteArrayInputStream(new byte[]{1}), new ByteArrayOutputStream(), 2));
    }

    @Test public void validSaveReplacesOriginal() throws Exception {
        File dest = payload("old.xlsx", new byte[]{1, 2});
        byte[] data = {5, 6, 7};
        File source = payload("new.xlsx", data);
        OfficeSaveIntegrity.writeLocal(source, dest, 100);
        assertArrayEquals(data, Files.readAllBytes(dest.toPath()));
    }
}
