package com.example.emulator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class EmulatorAudioPlayer(sampleRate: Int = 44100) {
    private var audioTrack: AudioTrack? = null
    @Volatile
    var isMuted: Boolean = false

    init {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        runCatching {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
        }
    }

    fun write(samples: ShortArray, count: Int) {
        if (isMuted || count <= 0) return
        val track = audioTrack ?: return
        runCatching {
            track.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING)
        }
    }

    fun pause() {
        runCatching { audioTrack?.pause() }
    }

    fun resume() {
        runCatching { audioTrack?.play() }
    }

    fun release() {
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }
        audioTrack = null
    }
}
