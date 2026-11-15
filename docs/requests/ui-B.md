# ui-B 계약 변경 요청

계약은 고치지 않았고, 아래는 지금 계약으로 구현한 방식과 원하는 변경이다.

## 1. 다시 보기: 겹의 순번

- 원하는 표시: "수목원 · 우리 가족 여행 · 첫 번째 겹"
- 지금 계약: `Recall(record, topic, yearsAgo)` 에는 그 기록이 주제의 몇 번째 겹인지가 없다(주제의 첫 기록 해를 화면이 알 수 없음).
- 지금 구현: 기록의 해로 대신 표시 → "수목원 · 우리 가족 여행 · 2026년의 겹" (`RecallScreen.kt` 의 `subLine`)
- 원하는 변경: `Recall` 에 `ringNumber: Int`(1 = 첫 겹) 추가. 추가되면 `subLine` 한 줄만 바꾸면 된다.

## 2. 한마디를 남긴 사람 이름

- 원하는 표시: 다시 보기 "지우 · 작년 목소리", 꾸미기 "\"아빠, 내년에 또 오자!\" · 지우 목소리"
- 지금 계약: `VoiceNote.authorId` 만 있고 `Screen.RecordEdit` / `Screen.Decorate` / `Screen.RecallView` 에 이름을 찾을 방법이 없다.
- 지금 구현: 이름 없이 한마디 글만 표시, 다시 보기는 "그날의 목소리"
- 원하는 변경: 세 화면 상태에 `noteAuthors: Map<String, String>`(authorId → 이름) 또는 `Screen.RecallView.voiceLabel: String?` 추가
