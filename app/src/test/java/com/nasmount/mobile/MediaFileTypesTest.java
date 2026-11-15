package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaFileTypesTest {
    @Test public void commonAudioFormatsAreRecognized() {
        assertTrue(MediaFileTypes.isAudio("노래.MP3"));
        assertTrue(MediaFileTypes.isAudio("녹음.FLAC"));
        assertTrue(MediaFileTypes.isAudio("음성.opus"));
        assertTrue(MediaFileTypes.isAudio("music.m4a"));
        assertFalse(MediaFileTypes.isAudio("video.mp4"));
        assertFalse(MediaFileTypes.isAudio("none"));
        assertFalse(MediaFileTypes.isAudio(null));
    }
    @Test public void imagesOpenInsideApp() {
        assertTrue(MediaFileTypes.isImage("포스터.PNG"));
        assertTrue(MediaFileTypes.isImage("photo.jpeg"));
        assertTrue(MediaFileTypes.isImage("animation.gif"));
        assertTrue(MediaFileTypes.isImage("phone.HEIC"));
        assertFalse(MediaFileTypes.isImage("slides.pptx"));
        assertFalse(MediaFileTypes.isImage(null));
    }
}
