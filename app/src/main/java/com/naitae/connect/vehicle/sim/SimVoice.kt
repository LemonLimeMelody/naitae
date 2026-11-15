package com.naitae.connect.vehicle.sim

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VoiceInput
import com.naitae.connect.vehicle.VoiceOutput
import com.naitae.connect.vehicle.VoiceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 음성 출력 시뮬레이터.
 * 기본은 소리를 내지 않고 읽는 데 걸릴 시간만큼만 기다린다(자막과 연출의 박자를 맞추기 위함).
 * SimSettings.speakAloud 가 켜져 있으면 기기의 합성 음성(ko-KR)으로 실제로 읽는다.
 */
internal class SimVoiceOutput(context: Context) : VoiceOutput {

    private val appContext: Context = context.applicationContext

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** 지금 읽는 문장의 끝 신호. true = 끝까지 읽음, false = 중간에 멈춤 */
    @Volatile
    private var current: CompletableDeferred<Boolean>? = null

    @Volatile
    private var engine: TextToSpeech? = null

    /** 합성 음성을 한 번 못 쓰면 다시 시도하지 않는다(문장마다 기다리게 되므로). */
    @Volatile
    private var engineFailed = false

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val utteranceSeq = AtomicInteger(0)

    override suspend fun speak(text: String): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)
        // 앞 문장이 남아 있으면 끊고 새 문장을 읽는다
        current?.complete(false)
        val done = CompletableDeferred<Boolean>()
        current = done
        _speaking.value = true
        SdkLog.api("TextToSpeech.speak(\"${shorten(text)}\")")
        var aloud = false
        return try {
            aloud = SimSettings.speakAloud && startPlatformSpeech(text, done)
            val limitMs = if (aloud) readingTimeMs(text) * 2 + ALOUD_GRACE_MS else readingTimeMs(text)
            // 소리 없이 읽을 때는 시간이 다 지난 것이 '다 읽음'이다
            val finished = withTimeoutOrNull(limitMs) { done.await() } ?: true
            SdkLog.event(if (finished) "TextToSpeech · onDone" else "TextToSpeech · onStop")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SdkLog.sys("TextToSpeech 시뮬레이터 오류: ${e.message}")
            Result.failure(e)
        } finally {
            // 취소되거나 시간이 넘으면 소리도 함께 멈춘다
            if (aloud && !done.isCompleted) stopEngine()
            pending.values.remove(done)
            if (current === done) {
                current = null
                _speaking.value = false
            }
        }
    }

    override fun stop() {
        val reading = current ?: return
        current = null
        _speaking.value = false
        SdkLog.api("TextToSpeech.stop()")
        reading.complete(false)
        stopEngine()
    }

    private fun stopEngine() {
        try {
            engine?.stop()
        } catch (e: Exception) {
            SdkLog.sys("합성 음성을 멈추지 못함: ${e.message}")
        }
    }

    /** 기기의 합성 음성으로 읽기 시작한다. 쓸 수 없으면 false(소리 없이 시간만 기다림). */
    private suspend fun startPlatformSpeech(text: String, done: CompletableDeferred<Boolean>): Boolean {
        val tts = ensureEngine() ?: return false
        val id = "sim-${utteranceSeq.incrementAndGet()}"
        pending[id] = done
        val code = try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (e: Exception) {
            TextToSpeech.ERROR
        }
        if (code != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        return true
    }

    private suspend fun ensureEngine(): TextToSpeech? {
        engine?.let { return it }
        if (engineFailed) return null
        val ready = CompletableDeferred<Boolean>()
        val created: TextToSpeech? = try {
            withContext(Dispatchers.Main) {
                TextToSpeech(appContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (created == null) return giveUpEngine(null, "만들지 못함")

        val initialized = withTimeoutOrNull(ENGINE_INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!initialized) return giveUpEngine(created, "초기화 실패")

        val language = try {
            created.setLanguage(Locale.KOREA)
        } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            return giveUpEngine(created, "한국어 음성 없음")
        }

        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                finish(utteranceId, true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finish(utteranceId, true)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finish(utteranceId, true)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                finish(utteranceId, false)
            }
        })
        engine = created
        SdkLog.sys("시뮬레이터 음성: 기기 합성 음성(ko-KR)으로 읽습니다")
        return created
    }

    private fun giveUpEngine(created: TextToSpeech?, reason: String): TextToSpeech? {
        engineFailed = true
        try {
            created?.shutdown()
        } catch (e: Exception) {
            // 이미 못 쓰는 엔진이라 닫기 실패는 넘어간다
        }
        SdkLog.sys("시뮬레이터 음성: 기기 합성 음성을 쓸 수 없어($reason) 소리 없이 읽습니다")
        return null
    }

    private fun finish(utteranceId: String?, finished: Boolean) {
        if (utteranceId == null) return
        pending.remove(utteranceId)?.complete(finished)
    }

    private companion object {
        const val READ_BASE_MS = 1_200L
        const val READ_MS_PER_CHAR = 95L
        const val READ_MAX_MS = 9_000L

        /** 합성 음성이 끝났다는 신호를 주지 않을 때를 대비한 여유 */
        const val ALOUD_GRACE_MS = 3_000L
        const val ENGINE_INIT_TIMEOUT_MS = 2_500L
        const val LOG_TEXT_MAX = 28

        fun readingTimeMs(text: String): Long =
            (READ_BASE_MS + READ_MS_PER_CHAR * text.length).coerceAtMost(READ_MAX_MS)

        fun shorten(text: String): String =
            if (text.length <= LOG_TEXT_MAX) text else text.take(LOG_TEXT_MAX - 1) + "…"
    }
}

/**
 * 음성 입력 시뮬레이터. 가상 콘솔이 넣어 둔 말(sayNext)이나 시나리오 기본값을
 * 한 글자씩 받아 적는 것처럼 partial 에 보여 준 뒤 돌려준다.
 */
internal class SimVoiceInput(private val vehicle: SimVehicle) : VoiceInput {

    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    /** 지금 듣는 중인 한 번의 취소 신호 */
    @Volatile
    private var session: CompletableDeferred<Unit>? = null

    override suspend fun listen(timeoutMs: Long): VoiceResult {
        session?.complete(Unit)
        val cancelled = CompletableDeferred<Unit>()
        session = cancelled
        _partial.value = ""
        SdkLog.api("SpeechToText.request() · ko-KR")
        try {
            val text = awaitUtterance(timeoutMs, cancelled)
            if (cancelled.isCompleted) return VoiceResult.NoSpeech
            if (text == null) {
                SdkLog.event("SpeechToText · onEndedRecognition() · 말 없음")
                return VoiceResult.NoSpeech
            }
            SdkLog.event("SpeechToText · onStartedRecognition()")
            if (pause(HEARING_LEAD_MS, cancelled)) return VoiceResult.NoSpeech
            for (end in 1..text.length) {
                _partial.value = text.substring(0, end)
                if (pause(TYPING_MS_PER_CHAR, cancelled)) return VoiceResult.NoSpeech
            }
            SdkLog.event("SpeechToText · onUpdated(\"$text\", isFinal = true)")
            return VoiceResult.Text(text)
        } finally {
            if (session === cancelled) session = null
        }
    }

    override fun cancel() {
        val listening = session ?: return
        session = null
        SdkLog.api("SpeechToText.stop()")
        listening.complete(Unit)
        _partial.value = ""
    }

    /** 돌려줄 말을 정한다. 정해 둔 말이 없으면 잠깐 기다리고(그사이 콘솔에서 넣으면 그 말), 끝내 없으면 null. */
    private suspend fun awaitUtterance(timeoutMs: Long, cancelled: CompletableDeferred<Unit>): String? {
        vehicle.takeQueuedUtterance()?.let { return it }
        vehicle.defaultUtterance()?.let { return it }
        val waitMs = NO_SPEECH_MS.coerceAtMost(timeoutMs.coerceAtLeast(0L))
        var waited = 0L
        while (waited < waitMs) {
            if (pause(POLL_MS, cancelled)) return null
            waited += POLL_MS
            vehicle.takeQueuedUtterance()?.let { return it }
        }
        return null
    }

    /** ms 만큼 쉰다. 그사이 취소되면 true. */
    private suspend fun pause(ms: Long, cancelled: CompletableDeferred<Unit>): Boolean =
        withTimeoutOrNull(ms) { cancelled.await() } != null

    private companion object {
        const val NO_SPEECH_MS = 1_500L
        const val POLL_MS = 100L

        /** 말을 시작하기 전의 짧은 틈 */
        const val HEARING_LEAD_MS = 400L
        const val TYPING_MS_PER_CHAR = 120L
    }
}
