package com.nasmount.mobile;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.*;

/** SAF cannot promise atomic rename of a document URI. Keep the old bytes
 * outside the disposable cache before requesting a truncating write. */
final class OfficeSafSave {
    private OfficeSafSave() {}

    static void write(ContentResolver resolver, Uri uri, File source,
                      File recoveryDirectory, long max) throws IOException {
        long expected = OfficeSaveIntegrity.requirePayload(source, max);
        byte[] hash = OfficeSaveIntegrity.digest(source);
        if (!recoveryDirectory.isDirectory() && !recoveryDirectory.mkdirs())
            throw new IOException("Cannot create Office recovery directory");
        File backup = File.createTempFile("office-original-", ".backup", recoveryDirectory);
        boolean saved = false;
        boolean backupComplete = false;
        boolean writeStarted = false;
        try {
            try (InputStream in = resolver.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(backup)) {
                if (in == null) throw new IOException("Original document is not readable");
                long size = 0;
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (n == 0) continue;
                    size += n;
                    if (size > max) throw new IOException("Original exceeds recovery limit");
                    out.write(buffer, 0, n);
                }
                if (size <= 0) throw new IOException("Original document is empty");
                out.getFD().sync();
            }
            backupComplete = true;
            writeStarted = true;
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(uri, "rwt")) {
                OfficeSaveIntegrity.copyExact(in, out, expected);
            }
            try (InputStream in = resolver.openInputStream(uri)) {
                OfficeSaveIntegrity.verify(in, expected, hash);
            }
            saved = true;
        } catch (Exception error) {
            // A provider may fail after truncating the URI. Do not overwrite
            // a changed nonempty revision. Restore only a confirmed empty URI.
            try {
                boolean empty;
                try (InputStream current = resolver.openInputStream(uri)) {
                    empty = current != null && current.read() == -1;
                }
                if (writeStarted && backupComplete && empty && backup.length() > 0) {
                    try (InputStream in = new FileInputStream(backup);
                         OutputStream out = resolver.openOutputStream(uri, "rwt")) {
                        OfficeSaveIntegrity.copyExact(in, out, backup.length());
                    }
                    try (InputStream in = resolver.openInputStream(uri)) {
                        OfficeSaveIntegrity.verify(in, backup.length(), OfficeSaveIntegrity.digest(backup));
                    }
                }
            } catch (Exception ignored) { /* original backup remains available */ }
            throw new IOException("Office save failed; local recovery copy retained", error);
        } finally {
            if (saved || !backupComplete) backup.delete();
        }
    }
}
