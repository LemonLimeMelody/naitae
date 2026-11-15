# sim 담당 요청

## 1. (선택) 매니페스트에 합성 음성 조회 선언 — app 담당

시뮬레이터의 음성 출력은 기본이 '소리 없음'이라 지금 그대로도 동작한다.
`SimSettings.speakAloud = true`로 기기의 합성 음성(`android.speech.tts.TextToSpeech`, ko-KR)을 실제로 들으려면,
Android 11(API 30) 이상에서는 아래 선언이 있어야 음성 엔진에 연결된다. 없으면 초기화가 실패하고 자동으로 소리 없는 방식으로 돌아간다(앱은 죽지 않음).

```xml
<manifest ...>
    <queries>
        <intent>
            <action android:name="android.intent.action.TTS_SERVICE" />
        </intent>
    </queries>
    ...
</manifest>
```

계약 변경 요청은 없음.
