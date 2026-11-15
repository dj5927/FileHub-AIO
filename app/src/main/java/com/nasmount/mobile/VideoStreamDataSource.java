package com.nasmount.mobile;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.io.InputStream;

/**
 * ExoPlayer seekable adapter for SMB/FTP/SFTP/WebDAV/cloud.
 * All reads are executed by Media3 loading threads, never the Android UI.
 */
@androidx.media3.common.util.UnstableApi
final class VideoStreamDataSource extends BaseDataSource {
    private final Models.RemoteProfile profile;
    private final String remotePath;
    private final long fileSize;
    private InputStream input;
    private Uri uri;
    private long remaining;
    private boolean opened;

    VideoStreamDataSource(Models.RemoteProfile profile, String path, long fileSize) {
        super(true);
        this.profile = profile;
        remotePath = RemoteProvider.relativePath(path);
        this.fileSize = fileSize;
    }

    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);
        if (spec.position < 0 ||
                (fileSize >= 0 && spec.position > fileSize))
            throw new IOException("영상 탐색 위치가 파일 범위를 벗어났습니다.");
        try {
            input = RemoteProvider.openStream(profile, remotePath, spec.position);
        } catch (Exception ex) {
            throw new IOException("원격 스트리밍 연결 실패: " +
                    ex.getClass().getSimpleName(), ex);
        }
        uri = spec.uri;
        long lengthFromFile = fileSize < 0 ? C.LENGTH_UNSET :
                Math.max(0, fileSize - spec.position);
        remaining = spec.length == C.LENGTH_UNSET ? lengthFromFile :
                (lengthFromFile == C.LENGTH_UNSET ? spec.length :
                        Math.min(spec.length, lengthFromFile));
        opened = true;
        transferStarted(spec);
        return remaining;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (input == null) return C.RESULT_END_OF_INPUT;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int take = remaining == C.LENGTH_UNSET ? length :
                (int)Math.min(length, remaining);
        int count = input.read(buffer, offset, take);
        if (count == -1) {
            if (remaining != C.LENGTH_UNSET && remaining > 0)
                throw new IOException("네트워크 스트림이 예상보다 일찍 종료되었습니다.");
            return C.RESULT_END_OF_INPUT;
        }
        if (remaining != C.LENGTH_UNSET) remaining -= count;
        bytesTransferred(count);
        return count;
    }

    @Nullable @Override public Uri getUri() { return uri; }

    @Override public void close() throws IOException {
        try {
            if (input != null) input.close();
        } finally {
            input = null;
            uri = null;
            remaining = 0;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    static final class Factory implements DataSource.Factory {
        private final Models.RemoteProfile profile;
        private final String path;
        private final long size;
        Factory(Models.RemoteProfile profile, String path, long size) {
            this.profile = profile;
            this.path = path;
            this.size = size;
        }
        @Override public DataSource createDataSource() {
            return new VideoStreamDataSource(profile, path, size);
        }
    }
}
