package com.dreamplayer.app

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/// [preferFfmpegAudio]: put the FFmpeg audio renderer AHEAD of the platform
/// MediaCodec audio renderers (Media3's EXTENSION_RENDERER_MODE_PREFER, for
/// audio only) — the same as Nuvio's "Decoder Priority: Prefer app decoders
/// (FFmpeg)". On the Fire TV Cube some streams (e.g. Reacher) stutter with the
/// platform audio decoder and play smoothly once FFmpeg decodes the audio.
/// FFmpeg outputs PCM, so with this on, HDMI passthrough is bypassed for every
/// format FFmpeg can decode (AC3, E-AC3, TrueHD, DTS...).
class DreamRenderersFactory(
    context: Context,
    private val preferFfmpegAudio: Boolean = false,
) : DefaultRenderersFactory(context) {
    init {
        setAllowedVideoJoiningTimeMs(0L)
    }

    companion object {
        /// Manual A/V sync: shifts audio relative to video (±5 s). Shared so
        /// the `setAudioDelay` channel handler can retune it mid-playback.
        val audioDelayProcessor = AudioDelayProcessor()
    }

    /// Inserts the audio-delay processor into every audio sink (platform and
    /// FFmpeg renderers both receive this sink from buildAudioRenderers).
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(audioDelayProcessor))
            .build()

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        codecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val mediaCodecIndex = out.size
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            codecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedJoiningTimeMs,
            out,
        )
        // Swap the stock MediaCodec video renderer for DreamVideoRenderer
        // (same settings) so Dolby Vision profile 7 can be decoded as its
        // HDR10 base layer on the HEVC hardware decoder.
        if (out.getOrNull(mediaCodecIndex) is MediaCodecVideoRenderer) {
            try {
                out[mediaCodecIndex] = DreamVideoRenderer(
                    MediaCodecVideoRenderer.Builder(context)
                        .setCodecAdapterFactory(getCodecAdapterFactory())
                        .setMediaCodecSelector(codecSelector)
                        .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
                        .setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler)
                        .setEventListener(eventListener)
                        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                )
            } catch (e: Exception) {
                Log.w("DreamRenderersFactory", "DreamVideoRenderer unavailable, keeping stock renderer.", e)
            }
        }
        // Add FfmpegVideoRenderer as ultimate fallback (after all MediaCodec
        // renderers). Used when hardware + software MediaCodec decoders both
        // fail (e.g. 10-bit HEVC on devices without 10-bit MediaCodec support).
        // Added at the END so HDR/DV hardware path stays primary.
        // Constructor: (allowedJoiningTimeMs, handler, eventListener, maxDroppedFramesToNotify)
        try {
            out.add(FfmpegVideoRenderer(allowedJoiningTimeMs, eventHandler, eventListener, 0))
            Log.i("DreamRenderersFactory", "Loaded FfmpegVideoRenderer as fallback.")
        } catch (e: Exception) {
            Log.w("DreamRenderersFactory", "FfmpegVideoRenderer unavailable, falling back to MediaCodec only.", e)
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        codecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val firstAudioIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            codecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        try {
            val ffmpeg = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
            if (preferFfmpegAudio) {
                // Media3 picks the FIRST renderer that handles a track, so
                // inserting before the MediaCodec audio renderer makes FFmpeg
                // win for every format it supports.
                out.add(firstAudioIndex, ffmpeg)
                Log.i("DreamRenderersFactory", "Loaded FfmpegAudioRenderer (preferred over MediaCodec).")
            } else {
                out.add(ffmpeg)
                Log.i("DreamRenderersFactory", "Loaded FfmpegAudioRenderer.")
            }
        } catch (e: Exception) {
            Log.w("DreamRenderersFactory", "FfmpegAudioRenderer unavailable, falling back to MediaCodec only.", e)
        }
    }
}
