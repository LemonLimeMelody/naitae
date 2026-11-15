package com.naitae.connect.vehicle.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VoiceInput
import com.naitae.connect.vehicle.VoiceOutput
import com.naitae.connect.vehicle.VoiceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/*
 * 음성 출력(TextToSpeech SDK)과 음성 입력(SpeechToText SDK).
 * 둘 다 차 안 엔진(ON_DEVICE)과 한국어(ko-KR)로 쓴다. ON_DEVICE 만 쓰면 앱 등록(registerApp)이 필요 없다.
 * TTS 와 STT 의 Mode · ErrorType · OnInitializeListener 는 이름이 같아서 Tts…/Stt… 별칭으로 가져온다.
 */

/** SDK 초기화가 실패한 뒤 다시 시도하기까지 두는 간격 */
private const val INIT_RETRY_MS = 30_000L
private const val INIT_TIMEOUT_MS = 5_000L

/** 음성 출력. speak() 는 다 읽을 때까지(또는 stop() 이 불릴 때까지) 기다린다. */
internal class PleosVoiceOutput(private val context: Context) : VoiceOutput {

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val initMutex = Mutex()
    private val speakMutex = Mutex()

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var initFailedAtMs = 0L

    @Volatile
    private var current: Utterance? = null

    /** stop() 이 불릴 때마다 올라간다. 차례를 기다리던 speak() 이 뒤늦게 읽기 시작하지 않게 한다. */
    private val stopCount = AtomicInteger(0)

    private class Utterance {
        val finished = CompletableDeferred<Result<Unit>>()

        @Volatile
        var started = false
    }

    private val listener = object : EventListener {
        override fun onReady() = Unit

        override fun onStart() {
            SdkLog.event("TextToSpeech.onStart")
            current?.started = true
        }

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            SdkLog.event("TextToSpeech.onDone")
            current?.finished?.complete(Result.success(Unit))
        }

        override fun onStop() {
            SdkLog.event("TextToSpeech.onStop")
            // 앞 문장을 멈춘 이벤트가 늦게 와서 아직 시작도 안 한 다음 문장을 끝내 버리지 않게 한다.
            val utterance = current
            if (utterance != null && utterance.started) utterance.finished.complete(Result.success(Unit))
        }

        override fun onError(errorType: TtsErrorType) {
            SdkLog.event("TextToSpeech.onError → ${errorType.name}")
            current?.finished?.complete(Result.failure(IllegalStateException("음성 출력 오류: ${errorType.name}")))
        }
    }

    override suspend fun speak(text: String): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)
        val stopsAtCall = stopCount.get()
        return speakMutex.withLock {
            val tts = ensureReady()
                ?: return@withLock Result.failure(IllegalStateException("음성 출력 서비스를 쓸 수 없음"))
            // 차례를 기다리거나 준비하는 사이에 stop() 이 불렸으면 읽지 않는다.
            if (stopsAtCall != stopCount.get()) return@withLock Result.success(Unit)
            val utterance = Utterance()
            current = utterance
            _speaking.value = true
            try {
                tts.speak(text, true)
                SdkLog.api("TextToSpeech.speak(\"${preview(text)}\")")
                val result = withTimeoutOrNull(speakTimeoutMs(text)) { utterance.finished.await() }
                if (result == null) {
                    SdkLog.sys("TextToSpeech 종료 이벤트가 오지 않아 멈춤")
                    safeSdk("TextToSpeech.stop()") { tts.stop() }
                }
                result ?: Result.failure(TimeoutException("음성 출력 종료 이벤트 없음"))
            } catch (c: CancellationException) {
                // 읽는 도중 호출한 쪽이 취소되면(화면 전환 등) 소리도 멈춘다.
                safeSdk("TextToSpeech.stop()") { tts.stop() }
                throw c
            } catch (t: Throwable) {
                SdkLog.sys("TextToSpeech.speak() 실패 · ${t.brief()}")
                Result.failure(t)
            } finally {
                if (current === utterance) current = null
                _speaking.value = false
            }
        }
    }

    override fun stop() {
        stopCount.incrementAndGet()
        val utterance = current ?: return
        val tts = engine
        if (tts != null) {
            safeSdk("TextToSpeech.stop()") {
                tts.stop()
                SdkLog.api("TextToSpeech.stop()")
            }
        }
        // SDK 의 onStop 을 기다리지 않고 바로 끝낸다.
        utterance.finished.complete(Result.success(Unit))
    }

    /** 첫 문장이 늦지 않게 미리 초기화해 둔다. */
    suspend fun warmUp() {
        ensureReady()
    }

    fun release() {
        stop()
        val tts = engine ?: return
        engine = null
        safeSdk("TextToSpeech.removeEventListener()") { tts.removeEventListener(listener) }
        safeSdk("TextToSpeech.release()") {
            tts.release()
            SdkLog.api("TextToSpeech.release()")
        }
    }

    private suspend fun ensureReady(): TextToSpeech? = engine ?: initMutex.withLock {
        engine?.let { return@withLock it }
        if (System.currentTimeMillis() - initFailedAtMs < INIT_RETRY_MS) return@withLock null
        val created = try {
            TextToSpeech(context, TtsMode.ON_DEVICE)
        } catch (t: Throwable) {
            SdkLog.sys("TextToSpeech 생성 실패 · ${t.brief()}")
            initFailedAtMs = System.currentTimeMillis()
            return@withLock null
        }
        val init = awaitSdk<Unit>("TextToSpeech.initialize()", INIT_TIMEOUT_MS) { ok, fail ->
            created.initialize(object : TtsInitListener {
                override fun onSuccess() = ok(Unit)

                override fun onFailure(errorType: TtsErrorType) =
                    fail(IllegalStateException("초기화 실패: ${errorType.name}"))
            })
        }
        SdkLog.api("TextToSpeech(ON_DEVICE).initialize() → " + init.fold({ "준비됨" }, { "실패 · ${it.brief()}" }))
        if (init.isFailure) {
            initFailedAtMs = System.currentTimeMillis()
            safeSdk("TextToSpeech.release()") { created.release() }
            return@withLock null
        }
        safeSdk("TextToSpeech.setLocale()") {
            created.setLocale(Locale.KOREA)
            SdkLog.api("TextToSpeech.setLocale(ko-KR)")
        }
        safeSdk("TextToSpeech.addEventListener()") {
            created.addEventListener(listener)
            SdkLog.api("TextToSpeech.addEventListener(listener)")
        }
        engine = created
        created
    }

    /** 문장 길이에 맞춘 안전 시간. 종료 이벤트가 오지 않아도 speak() 가 영원히 멈춰 있지 않게 한다. */
    private fun speakTimeoutMs(text: String): Long = 10_000L + text.length * 350L

    private fun preview(text: String): String = if (text.length <= 28) text else text.take(28) + "…"
}

/** 음성 입력. 한 번 듣고 결과 하나를 돌려준다. 듣는 동안 인식된 글은 partial 로 흘려보낸다. */
internal class PleosVoiceInput(private val context: Context) : VoiceInput {

    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    private val initMutex = Mutex()
    private val listenMutex = Mutex()

    @Volatile
    private var engine: SpeechToText? = null

    @Volatile
    private var initFailedAtMs = 0L

    @Volatile
    private var current: Listening? = null

    private class Listening {
        val finished = CompletableDeferred<VoiceResult>()

        /** 말이 끝났다는 이벤트(onEndedRecognition)가 왔다. 최종 결과(onUpdated 완료)가 뒤따를 수 있어 바로 끝내지 않는다. */
        val ended = CompletableDeferred<Unit>()

        /** 이번 듣기에서 SDK 이벤트(onReady · onStartedRecognition · onUpdated)를 하나라도 받았다. */
        @Volatile
        var opened = false

        @Volatile
        var lastText = ""

        fun finishWithHeard() {
            finished.complete(if (lastText.isNotEmpty()) VoiceResult.Text(lastText) else VoiceResult.NoSpeech)
        }
    }

    private val listener = object : ResultListener {
        override fun onReady() {
            SdkLog.event("SpeechToText.onReady")
            current?.opened = true
        }

        override fun onStartedRecognition() {
            SdkLog.event("SpeechToText.onStartedRecognition")
            current?.opened = true
        }

        override fun onUpdated(stt: String, completed: Boolean) {
            val listening = current ?: return
            listening.opened = true
            val text = stt.trim()
            if (text.isNotEmpty()) {
                listening.lastText = text
                _partial.value = text
            }
            if (completed) {
                SdkLog.event("SpeechToText.onUpdated(완료) → \"${listening.lastText}\"")
                listening.finishWithHeard()
            }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onEndedRecognition() {
            SdkLog.event("SpeechToText.onEndedRecognition")
            // 앞선 듣기를 닫으며(stop) 뒤늦게 온 종료 이벤트가 이번 듣기를 끝내지 않도록, 이번 듣기의 이벤트를 받은 뒤의 것만 받는다.
            val listening = current ?: return
            if (listening.opened) listening.ended.complete(Unit)
        }

        override fun onError(errorType: SttErrorType) {
            SdkLog.event("SpeechToText.onError → ${errorType.name}")
            val listening = current ?: return
            when {
                // 말이 없어서 난 시간 초과는 오류가 아니라 '말 없음'이다.
                errorType == SttErrorType.TIMEOUT -> listening.finishWithHeard()
                listening.lastText.isNotEmpty() -> listening.finishWithHeard()
                else -> listening.finished.complete(VoiceResult.Failed("음성 인식 오류: ${errorType.name}"))
            }
        }
    }

    /**
     * timeoutMs 는 말을 기다리는 시간이다. 그 안에 말이 들리기 시작했으면 끝맺을 수 있게 조금 더 기다리고,
     * 그래도 끝나지 않으면 그때까지 인식된 글을 돌려준다.
     */
    override suspend fun listen(timeoutMs: Long): VoiceResult = listenMutex.withLock {
        val stt = ensureReady() ?: return@withLock VoiceResult.Failed("음성 인식 서비스를 쓸 수 없음")
        val listening = Listening()
        current = listening
        _partial.value = ""
        try {
            stt.request()
            SdkLog.api("SpeechToText.request() · ON_DEVICE · ko-KR")
            var result = withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) {
                select<VoiceResult?> {
                    listening.finished.onAwait { it }
                    listening.ended.onAwait { null }
                }
            }
            if (result == null) {
                // 말 끝 이벤트가 최종 결과보다 먼저 올 수 있어 잠깐 더 기다린다. 말하는 중에 시간이 다 됐으면 끝맺을 시간을 준다.
                val graceMs = when {
                    listening.ended.isCompleted -> END_GRACE_MS
                    listening.lastText.isNotEmpty() -> FINISH_GRACE_MS
                    else -> 0L
                }
                if (graceMs > 0L) result = withTimeoutOrNull(graceMs) { listening.finished.await() }
            }
            val heard = result
                ?: if (listening.lastText.isNotEmpty()) VoiceResult.Text(listening.lastText) else VoiceResult.NoSpeech
            SdkLog.sys(
                when (heard) {
                    is VoiceResult.Text -> "음성 입력 결과 → \"${heard.text}\""
                    VoiceResult.NoSpeech -> "음성 입력 결과 → 말 없음"
                    is VoiceResult.Failed -> "음성 입력 결과 → 실패 · ${heard.reason}"
                },
            )
            heard
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            SdkLog.sys("SpeechToText.request() 실패 · ${t.brief()}")
            VoiceResult.Failed(t.brief())
        } finally {
            current = null
            // 인식이 끝났거나 그만 들을 때는 stop() 으로 마이크를 닫는다.
            safeSdk("SpeechToText.stop()") {
                stt.stop()
                SdkLog.api("SpeechToText.stop()")
            }
        }
    }

    override fun cancel() {
        // listen() 이 '말 없음'으로 끝나고, 그 마무리에서 마이크를 닫는다.
        current?.finished?.complete(VoiceResult.NoSpeech)
    }

    suspend fun warmUp() {
        ensureReady()
    }

    fun release() {
        cancel()
        val stt = engine ?: return
        engine = null
        safeSdk("SpeechToText.removeListener()") { stt.removeListener(listener) }
        safeSdk("SpeechToText.release()") {
            stt.release()
            SdkLog.api("SpeechToText.release()")
        }
    }

    private suspend fun ensureReady(): SpeechToText? = engine ?: initMutex.withLock {
        engine?.let { return@withLock it }
        if (System.currentTimeMillis() - initFailedAtMs < INIT_RETRY_MS) return@withLock null
        val created = try {
            SpeechToText(context, SttMode.ON_DEVICE)
        } catch (t: Throwable) {
            SdkLog.sys("SpeechToText 생성 실패 · ${t.brief()}")
            initFailedAtMs = System.currentTimeMillis()
            return@withLock null
        }
        val init = awaitSdk<Unit>("SpeechToText.initialize()", INIT_TIMEOUT_MS) { ok, fail ->
            created.initialize(object : SttInitListener {
                override fun onSuccess() = ok(Unit)

                override fun onFailure(errorType: SttErrorType) =
                    fail(IllegalStateException("초기화 실패: ${errorType.name}"))
            })
        }
        SdkLog.api("SpeechToText(ON_DEVICE).initialize() → " + init.fold({ "준비됨" }, { "실패 · ${it.brief()}" }))
        if (init.isFailure) {
            initFailedAtMs = System.currentTimeMillis()
            safeSdk("SpeechToText.release()") { created.release() }
            return@withLock null
        }
        safeSdk("SpeechToText.setLocale()") {
            created.setLocale(Locale.KOREA)
            SdkLog.api("SpeechToText.setLocale(ko-KR)")
        }
        safeSdk("SpeechToText.addListener()") {
            created.addListener(listener)
            SdkLog.api("SpeechToText.addListener(listener)")
        }
        engine = created
        created
    }

    private companion object {
        const val FINISH_GRACE_MS = 4_000L

        /** 말 끝 이벤트 뒤에 최종 결과를 기다리는 시간 */
        const val END_GRACE_MS = 1_500L
    }
}
