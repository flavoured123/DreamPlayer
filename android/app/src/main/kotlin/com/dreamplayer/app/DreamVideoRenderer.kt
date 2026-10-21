package com.dreamplayer.app

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * MediaCodecVideoRenderer that can play a Dolby Vision stream through the
 * plain HEVC hardware decoder ("base layer" = HDR10).
 *
 * Why: Media3 only maps DV profiles 4/8 to HEVC. A profile 7 (UHD Blu-ray
 * remux, dual layer) is always handed to the device's DV decoder — on a Fire
 * TV Cube that decoder does not really handle P7, fails (often after a seek)
 * and the app then fell back to FFmpeg *software* 4K decoding, which the
 * box cannot do in real time: video froze and the whole UI stopped
 * responding to the remote.
 *
 * Whether to use the base layer is decided per format by
 * [PlayerCodecs.useDvBaseLayer]; everything else behaves exactly like the
 * stock renderer.
 */
@UnstableApi
class DreamVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(selectorFor(mediaCodecSelector, format), format)

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> =
        super.getDecoderInfos(selectorFor(mediaCodecSelector, format), format, requiresSecureDecoder)

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: MediaCodecVideoRenderer.CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat {
        val mediaFormat = super.getMediaFormat(
            format,
            codecMimeType,
            codecMaxValues,
            codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround,
            tunnelingAudioSessionId,
        )
        // Media3 copies the DV profile (e.g. 0x80 for P7) into KEY_PROFILE.
        // An HEVC decoder must see an HEVC profile instead.
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
            codecMimeType == MimeTypes.VIDEO_H265
        ) {
            mediaFormat.setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
        }
        return mediaFormat
    }

    private fun selectorFor(selector: MediaCodecSelector, format: Format): MediaCodecSelector {
        if (!PlayerCodecs.useDvBaseLayer(format)) return selector
        return MediaCodecSelector { mimeType, secure, tunneling ->
            selector.getDecoderInfos(
                if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) MimeTypes.VIDEO_H265 else mimeType,
                secure,
                tunneling,
            )
        }
    }
}
