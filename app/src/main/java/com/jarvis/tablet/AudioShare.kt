package com.jarvis.tablet

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns PCM into an .m4a voice message and hands it to WhatsApp. */
object AudioShare {

    fun encodeM4a(pcm: ShortArray, rate: Int, out: File): Boolean {
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var started = false
            val info = MediaCodec.BufferInfo()
            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) bb.putShort(s)
            val bytes = bb.array()
            var inPos = 0
            var inputDone = false
            var outputDone = false
            var ptsUs = 0L
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        val n = (minOf(buf.remaining(), bytes.size - inPos) / 2) * 2
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(bytes, inPos, n)
                            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                            inPos += n
                            ptsUs += (n / 2) * 1_000_000L / rate
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            codec.stop(); codec.release()
            if (started) muxer.stop()
            muxer.release()
            started
        } catch (e: Exception) {
            false
        }
    }

    /** puts the file in Music/JARVIS through MediaStore (no storage permission) and returns a shareable content:// uri */
    fun saveAudio(ctx: Context, file: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/JARVIS")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: return null
            r.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            v.clear()
            v.put(MediaStore.Audio.Media.IS_PENDING, 0)
            r.update(uri, v, null, null)
            uri
        } catch (e: Exception) {
            null
        }
    }

    fun whatsappShareIntent(ctx: Context, uri: Uri, number: String?): Intent {
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null } ?: "com.whatsapp"
        val i = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .setPackage(pkg)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        i.clipData = ClipData.newRawUri("", uri)
        if (!number.isNullOrBlank()) i.putExtra("jid", "$number@s.whatsapp.net")
        return i
    }
}
