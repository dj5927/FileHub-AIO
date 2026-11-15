package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AudioPlaybackLabelsTest {
    @Test public void distinguishesCommonAudioCodecs() {
        assertEquals("AAC", AudioPlaybackLabels.codecName("audio/mp4a-latm", "mp4a.40.2"));
        assertEquals("E-AC3", AudioPlaybackLabels.codecName("audio/eac3", null));
        assertEquals("E-AC3 Atmos", AudioPlaybackLabels.codecName("audio/eac3-joc", null));
        assertEquals("TrueHD", AudioPlaybackLabels.codecName("audio/true-hd", null));
        assertEquals("DTS-HD", AudioPlaybackLabels.codecName("audio/vnd.dts.hd", null));
    }

    @Test public void unknownAndCodecIdentifierFallbacks() {
        assertEquals("AAC", AudioPlaybackLabels.codecName(null, "mp4a.40.2"));
        assertEquals("알 수 없음", AudioPlaybackLabels.codecName(null, null));
        assertEquals("ALAC", AudioPlaybackLabels.codecName("audio/alac", null));
    }

    @Test public void reportsDecoderTypeFromActualDecoderNameOnly() {
        assertEquals("확인 중", AudioPlaybackLabels.decoderKind(null));
        assertEquals("FFmpeg (NextLib)", AudioPlaybackLabels.decoderKind("ffmpeg7.1-eac3"));
        assertEquals("Android MediaCodec", AudioPlaybackLabels.decoderKind("c2.qti.aac.decoder"));
        assertEquals("Android MediaCodec", AudioPlaybackLabels.decoderKind("OMX.google.aac.decoder"));
        assertEquals("기타 디코더", AudioPlaybackLabels.decoderKind("custom_decoder"));
    }

    @Test public void audioChannelsAndSampleRate() {
        assertEquals("2채널 (스테레오)", AudioPlaybackLabels.channelLabel(2));
        assertEquals("6채널 (5.1)", AudioPlaybackLabels.channelLabel(6));
        assertEquals("8채널 (7.1)", AudioPlaybackLabels.channelLabel(8));
        assertEquals("정보 없음", AudioPlaybackLabels.channelLabel(-1));
        assertEquals("48 kHz", AudioPlaybackLabels.sampleRateLabel(48000));
        assertEquals("44.1 kHz", AudioPlaybackLabels.sampleRateLabel(44100));
        assertEquals("정보 없음", AudioPlaybackLabels.sampleRateLabel(-1));
    }
}
