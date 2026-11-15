package com.naitae.connect.app

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.naitae.connect.vehicle.RecordedVoicePlayer
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/**
 * 녹음한 목소리 재생: assets/<asset> 파일을 MediaPlayer 로 끝까지 재생한다.
 * 파일이 없으면 false 를 돌려주고, 부른 쪽이 TTS 로 대신 읽는다.
 *
 * 오디오 포커스는 docs/PLEOS_NOTES.md 6.3절을 따른다: 재생 전에 요청하고(음악은 잠깐 줄어듦),
 * 포커스를 잃으면 바로 멈추고, 끝나면 돌려준다.
 */
internal class AssetVoicePlayer(context: Context) : RecordedVoicePlayer {

    private val appContext = context.applicationContext
    private val audio: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 재생 한 번. 메인 스레드에서만 만들고 끝낸다. */
    private class Playback(
        val player: MediaPlayer,
        val continuation: CancellableContinuation<Boolean>,
    ) {
        var focus: AudioFocusRequest? = null
        var ended = false
    }

    private var current: Playback? = null

    override suspend fun play(asset: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val descriptor = open(asset) ?: return@withContext false
        // 앞선 재생이 남아 있으면 끊고 새로 재생한다.
        stop()
        suspendCancellableCoroutine<Boolean> { continuation ->
            val playback = Playback(MediaPlayer(), continuation)
            current = playback
            // 취소는 어느 스레드에서든 올 수 있어 메인으로 넘겨 정리한다.
            continuation.invokeOnCancellation { main.post { end(playback, completed = false) } }
            try {
                val player = playback.player
                player.setAudioAttributes(attributes)
                descriptor.use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                player.setOnPreparedListener { begin(playback) }
                player.setOnCompletionListener { end(playback, completed = true) }
                player.setOnErrorListener { _, what, extra ->
                    SdkLog.sys("녹음 재생 오류($asset): $what/$extra")
                    end(playback, completed = false)
                    true
                }
                player.prepareAsync()
                SdkLog.api("MediaPlayer.prepareAsync(assets/$asset)")
            } catch (e: Exception) {
                SdkLog.sys("녹음 재생 실패($asset): ${e.message ?: e.javaClass.simpleName}")
                end(playback, completed = false)
            }
        }
    }

    override fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) stopNow() else main.post { stopNow() }
    }

    /** 멈춘 재생은 '재생함'으로 돌려준다. 멈춘 뒤에 TTS 가 같은 말을 다시 읽지 않게 하기 위함이다. */
    private fun stopNow() {
        current?.let { end(it, completed = true) }
    }

    /** 준비가 끝나면 오디오 포커스를 얻고 재생한다. 포커스를 못 얻으면(통화 중 등) 재생하지 않는다. */
    private fun begin(playback: Playback) {
        if (playback.ended) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        end(playback, completed = true)
                    }
                },
                main,
            )
            .build()
        val granted = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            end(playback, completed = false)
            return
        }
        playback.focus = request
        try {
            playback.player.start()
        } catch (e: IllegalStateException) {
            end(playback, completed = false)
        }
    }

    /** 재생을 끝내고 자원과 포커스를 돌려준다. 여러 번 불려도 한 번만 한다. */
    private fun end(playback: Playback, completed: Boolean) {
        if (playback.ended) return
        playback.ended = true
        if (current === playback) current = null
        try {
            playback.player.release()
        } catch (e: Exception) {
            // 이미 해제된 재생기는 넘어간다
        }
        playback.focus?.let { audio?.abandonAudioFocusRequest(it) }
        if (playback.continuation.isActive) playback.continuation.resume(completed)
    }

    /** 자산 파일을 연다. 없으면 null (압축되지 않는 mp3·m4a·ogg·wav 를 넣는다). */
    private fun open(asset: String): AssetFileDescriptor? =
        try {
            appContext.assets.openFd(asset)
        } catch (e: IOException) {
            null
        }
}
