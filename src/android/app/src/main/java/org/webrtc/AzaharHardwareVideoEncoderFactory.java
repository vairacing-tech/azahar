// SPDX-FileCopyrightText: 2026 Azahar2S contributors
// SPDX-License-Identifier: GPL-2.0-or-later

package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Map;

public class AzaharHardwareVideoEncoderFactory implements VideoEncoderFactory {
    private static final String TAG = "AzaharHardwareVideoEncoderFactory";

    private final EglBase14.Context sharedContext;
    private final boolean enableH264HighProfile;
    private final Predicate<MediaCodecInfo> codecAllowedPredicate;
    private final int keyFrameIntervalSec;
    private final int maxBFrames;
    private final int qpPMax;

    public AzaharHardwareVideoEncoderFactory(
            EglBase.Context sharedContext,
            boolean enableH264HighProfile,
            Predicate<MediaCodecInfo> codecAllowedPredicate,
            int keyFrameIntervalSec,
            int maxBFrames,
            int qpPMax) {
        this.sharedContext = sharedContext instanceof EglBase14.Context
                ? (EglBase14.Context) sharedContext
                : null;
        this.enableH264HighProfile = enableH264HighProfile;
        this.codecAllowedPredicate = codecAllowedPredicate;
        this.keyFrameIntervalSec = keyFrameIntervalSec;
        this.maxBFrames = maxBFrames;
        this.qpPMax = qpPMax;
        if (this.sharedContext == null) {
            Logging.w(TAG, "No shared EglBase.Context. Encoders will not use texture mode.");
        }
    }

    @Override
    public VideoEncoder createEncoder(VideoCodecInfo info) {
        if (!VideoCodecMimeType.H264.name().equalsIgnoreCase(info.name)) {
            return null;
        }

        MediaCodecInfo codec = findCodecForType(VideoCodecMimeType.H264);
        if (codec == null) {
            return null;
        }

        String codecName = codec.getName();
        MediaCodecInfo.CodecCapabilities capabilities =
                codec.getCapabilitiesForType(VideoCodecMimeType.H264.mimeType());
        Integer surfaceColorFormat =
                MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, capabilities);
        Integer yuvColorFormat =
                MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, capabilities);

        boolean isHighProfile = H264Utils.isSameH264Profile(
                info.params,
                MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, true));
        boolean isBaselineProfile = H264Utils.isSameH264Profile(
                info.params,
                MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, false));
        if (!isHighProfile && !isBaselineProfile) {
            return null;
        }
        if (isHighProfile && !isH264HighProfileSupported(codec)) {
            return null;
        }

        return new HardwareVideoEncoder(
                new TuningMediaCodecWrapperFactory(keyFrameIntervalSec, maxBFrames, qpPMax),
                codecName,
                VideoCodecMimeType.H264,
                surfaceColorFormat,
                yuvColorFormat,
                info.params,
                keyFrameIntervalSec,
                0,
                new BaseBitrateAdjuster(),
                sharedContext);
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        ArrayList<VideoCodecInfo> supportedCodecs = new ArrayList<>();
        MediaCodecInfo codec = findCodecForType(VideoCodecMimeType.H264);
        if (codec == null) {
            return new VideoCodecInfo[0];
        }

        if (isH264HighProfileSupported(codec)) {
            supportedCodecs.add(new VideoCodecInfo(
                    VideoCodecMimeType.H264.name(),
                    MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, true),
                    new ArrayList<>()));
        }
        supportedCodecs.add(new VideoCodecInfo(
                VideoCodecMimeType.H264.name(),
                MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, false),
                new ArrayList<>()));
        return supportedCodecs.toArray(new VideoCodecInfo[0]);
    }

    private MediaCodecInfo findCodecForType(VideoCodecMimeType type) {
        MediaCodecInfo[] codecInfos = new android.media.MediaCodecList(
                android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos();
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (isSupportedCodec(codecInfo, type)) {
                return codecInfo;
            }
        }
        return null;
    }

    private boolean isSupportedCodec(MediaCodecInfo codecInfo, VideoCodecMimeType type) {
        if (!codecInfo.isEncoder()) {
            return false;
        }
        if (!MediaCodecUtils.codecSupportsType(codecInfo, type)) {
            return false;
        }
        MediaCodecInfo.CodecCapabilities capabilities =
                codecInfo.getCapabilitiesForType(type.mimeType());
        if (MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, capabilities) == null &&
                MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, capabilities) == null) {
            return false;
        }
        return MediaCodecUtils.isHardwareAccelerated(codecInfo) &&
                (codecAllowedPredicate == null || codecAllowedPredicate.test(codecInfo));
    }

    private boolean isH264HighProfileSupported(MediaCodecInfo codecInfo) {
        if (!enableH264HighProfile || Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            return false;
        }
        try {
            MediaCodecInfo.CodecCapabilities capabilities =
                    codecInfo.getCapabilitiesForType(VideoCodecMimeType.H264.mimeType());
            for (MediaCodecInfo.CodecProfileLevel profileLevel : capabilities.profileLevels) {
                if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) {
                    return true;
                }
            }
        } catch (IllegalArgumentException e) {
            Logging.w(TAG, "Could not query H.264 profile support for " + codecInfo.getName());
        }
        return false;
    }

    private static class TuningMediaCodecWrapperFactory implements MediaCodecWrapperFactory {
        private final MediaCodecWrapperFactory delegate = new MediaCodecWrapperFactoryImpl();
        private final int keyFrameIntervalSec;
        private final int maxBFrames;
        private final int qpPMax;

        TuningMediaCodecWrapperFactory(int keyFrameIntervalSec, int maxBFrames, int qpPMax) {
            this.keyFrameIntervalSec = keyFrameIntervalSec;
            this.maxBFrames = maxBFrames;
            this.qpPMax = qpPMax;
        }

        @Override
        public MediaCodecWrapper createByCodecName(String codecName) throws IOException {
            return new TuningMediaCodecWrapper(
                    delegate.createByCodecName(codecName),
                    keyFrameIntervalSec,
                    maxBFrames,
                    qpPMax);
        }
    }

    private static class TuningMediaCodecWrapper implements MediaCodecWrapper {
        private final MediaCodecWrapper delegate;
        private final int keyFrameIntervalSec;
        private final int maxBFrames;
        private final int qpPMax;

        TuningMediaCodecWrapper(
                MediaCodecWrapper delegate,
                int keyFrameIntervalSec,
                int maxBFrames,
                int qpPMax) {
            this.delegate = delegate;
            this.keyFrameIntervalSec = keyFrameIntervalSec;
            this.maxBFrames = maxBFrames;
            this.qpPMax = qpPMax;
        }

        @Override
        public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, maxBFrames);
            }
            if (qpPMax >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                format.setInteger(MediaFormat.KEY_VIDEO_QP_P_MAX, qpPMax);
            }
            format.setInteger("vendor.qti-ext-enc-low-latency.enable", 1);
            Logging.d(TAG, "Configured H.264 encoder: GOP=" + keyFrameIntervalSec +
                    "s, max-bframes=" + maxBFrames + ", qp-p-max=" +
                    (qpPMax >= 0 ? Integer.toString(qpPMax) : "default"));
            delegate.configure(format, surface, crypto, flags);
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public void release() {
            delegate.release();
        }

        @Override
        public int dequeueInputBuffer(long timeoutUs) {
            return delegate.dequeueInputBuffer(timeoutUs);
        }

        @Override
        public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
            delegate.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
        }

        @Override
        public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) {
            return delegate.dequeueOutputBuffer(info, timeoutUs);
        }

        @Override
        public void releaseOutputBuffer(int index, boolean render) {
            delegate.releaseOutputBuffer(index, render);
        }

        @Override
        public MediaFormat getInputFormat() {
            return delegate.getInputFormat();
        }

        @Override
        public MediaFormat getOutputFormat() {
            return delegate.getOutputFormat();
        }

        @Override
        public MediaFormat getOutputFormat(int index) {
            return delegate.getOutputFormat(index);
        }

        @Override
        public ByteBuffer getInputBuffer(int index) {
            return delegate.getInputBuffer(index);
        }

        @Override
        public ByteBuffer getOutputBuffer(int index) {
            return delegate.getOutputBuffer(index);
        }

        @Override
        public Surface createInputSurface() {
            return delegate.createInputSurface();
        }

        @Override
        public void setParameters(Bundle params) {
            delegate.setParameters(params);
        }

        @Override
        public MediaCodecInfo getCodecInfo() {
            return delegate.getCodecInfo();
        }
    }
}
