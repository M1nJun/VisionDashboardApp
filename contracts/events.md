# Agent → Server 이벤트 계약 (v1)

Agent(검사 PC, C#)와 Server(중앙 PC, Spring Boot) 사이의 유일한 인터페이스.
이 문서와 어긋나는 구현은 어느 쪽이든 버그다.

## 설계 원칙 세 가지

1. **1 물리 유닛 = 1 이벤트.** 한 제품이 검사 항목 여러 개에서 동시에 불량이어도
   이벤트는 하나이고, 실패한 항목들은 그 이벤트의 `items[]` 배열이다.
   이전 설계는 항목마다 이벤트를 보내고 "첫 번째만 델타 1"이라는 규칙으로 중복
   집계를 막았는데, 그 규칙을 아는 코드와 모르는 코드가 갈리면서 화면마다 숫자가
   달라졌다. 이제 델타 필드는 존재하지 않는다 — 이벤트 하나가 곧 유닛 하나다.

2. **Agent는 세지 않는다.** Agent는 "무슨 일이 있었는지"만 보고하고, 카운트·비율·
   집계는 전부 서버가 계산한다.

3. **판정 코드는 데이터다.** `judgement`는 자유 문자열이고, 그 값이 유효한지는
   서버가 `vision-catalog.json`을 보고 판단한다. 새 판정이 생겨도 계약은 그대로다.

---

## 1. 이벤트 전송

```
POST /api/ingest/events
Content-Type: application/json
```

한 번에 여러 건을 보낸다. 봉투(envelope)가 신원을 한 번만 담고, 개별 이벤트는
내용만 담는다.

```json
{
  "agentId": "C-2_EXAMPLE_C",
  "line": "C-2",
  "visionKey": "EXAMPLE_C",
  "agentVersion": "2.0.0",
  "sentAt": "2026-08-25T14:03:11.482+09:00",
  "events": [ /* 아래 세 종류 중 하나씩, 파일에 나타난 순서대로 */ ]
}
```

- `agentId`는 항상 `<line>_<visionKey>`.
- `visionKey`는 `vision-catalog.json`에 있는 키여야 한다. 없으면 배치 전체를
  **400으로 거절**한다 (오타를 조용히 삼키지 않는다).
- `events` 배열은 **파일에 나타난 순서**여야 한다. 서버는 순서대로 처리한다.
- 배치 최대 크기는 500건.

### 응답

```json
{ "accepted": 48, "replayed": 2, "rejected": [] }
```

- HTTP 200 → Agent는 파일 오프셋을 전진시킨다.
- `replayed`는 오류가 아니다. 이미 처리한 유닛을 다시 받은 것이고 정상 동작이다.
- `rejected`가 비어있지 않으면 `[{ "index": 7, "reason": "..." }]` 형태로 어떤
  이벤트가 왜 거절됐는지 담긴다. 이 경우에도 200이며 Agent는 전진한다 —
  거절 사유는 데이터 문제이지 전송 문제가 아니므로 재시도해도 똑같이 거절된다.
- HTTP 5xx → Agent는 **오프셋을 전진시키지 않고** 다음 폴링에 재전송한다.

---

## 2. `UNIT_INSPECTED`

제품 하나가 검사를 통과했거나 불량이 났다. 가장 빈번한 이벤트.

```json
{
  "type": "UNIT_INSPECTED",
  "sourceFile": "MDL_20260825.csv",
  "unitSeq": 10432,
  "lotId": "L2608250001",
  "modelId": "MDL",
  "cellId": "C0001234",
  "judgement": "NG",
  "occurredAt": "2026-08-25T14:03:10.900+09:00",
  "items": [
    { "name": "CHECK_A", "rawValue": "14.16007", "side": null },
    { "name": "CHECK_B", "rawValue": "24.59380", "side": null }
  ],
  "images": [
    { "set": "LEFT",  "kind": "MAIN",    "path": "F:\\Files\\Image\\20260825\\a.jpg" },
    { "set": "LEFT",  "kind": "OVERLAY", "path": "F:\\Files\\Image\\20260825\\a_ov.jpg" },
    { "set": "RIGHT", "kind": "MAIN",    "path": "F:\\Files\\Image\\20260825\\b.jpg" },
    { "set": "RIGHT", "kind": "OVERLAY", "path": "F:\\Files\\Image\\20260825\\b_ov.jpg" }
  ],
  "warnings": []
}
```

| 필드 | 규칙 |
|---|---|
| `unitSeq` | CSV의 `NO`. **같은 `sourceFile` 안에서 반드시 증가**해야 한다. 서버는 이 값으로 재전송을 걸러낸다. |
| `judgement` | `"OK"` · 카탈로그가 그 비전 타입에 정의한 불량 코드 · `"UNKNOWN"` 중 하나. |
| `items` | `judgement`가 OK면 반드시 빈 배열. 불량이면 최소 1개. |
| `items[].name` | 필터·그룹핑 키. 웰딩은 `JUDGE-DEFECT` 값, 스캔 모드는 항목 컬럼명. |
| `items[].rawValue` | 짝 컬럼의 원본 문자열. 없으면 `null`. **버리지 않는다** — 사후 복구가 불가능한 유일한 데이터다. |
| `images` | 파일 하나당 원소 하나. main과 overlay가 별개 원소인 이유는, 하나가 없어도 다른 하나는 살리기 위해서다. |
| `images[].set` | 카탈로그의 `images.sets` 키 (`LOWER`/`UPPER`/`LEFT`/`RIGHT`/`PLUS`/`MINUS`/`MAIN`). |
| `warnings` | 파싱 중 이상 징후. 이벤트를 버리는 대신 여기 남긴다. |

### `judgement: "UNKNOWN"`

CSV의 판정 값이 카탈로그의 어떤 코드에도 해당하지 않을 때. 생산량(`inspected_count`)에는
포함되고 불량으로는 세지 않으며, `unknown_count`가 올라간다. 조용히 버리면 생산량이
비게 되므로 버리지 않는다.

### 항목을 못 찾은 불량

판정은 불량인데 어느 항목이 실패했는지 스캔으로 찾지 못한 경우, 항목 하나를
`name: "UNSPECIFIED"`로 넣고 `warnings`에 사유를 남긴다. 불량 건수는 그대로 1이다.

---

## 3. `LOT_CHANGED`

```json
{
  "type": "LOT_CHANGED",
  "sourceFile": "MDL_20260825.csv",
  "oldLotId": "L2608250001",
  "newLotId": "L2608250002",
  "detectedAtUnitSeq": 10433,
  "occurredAt": "2026-08-25T14:03:11.100+09:00"
}
```

- **배치 안에서 새 랏의 첫 `UNIT_INSPECTED`보다 반드시 앞에 와야 한다.**
- Agent는 랏 상태를 **CSV 파일이 아니라 agent 단위로** 들고 있어야 한다. 파일 단위로
  들고 있으면 날짜가 바뀌어 새 CSV로 넘어갈 때 첫 행에서 변경 감지가 되지 않아,
  카운터가 리셋되지 않은 채 랏 번호만 바뀐다.
- `oldLotId`는 agent가 최초 기동해서 아직 아무 랏도 모를 때만 `null`이다. 이때 서버는
  이력을 닫지 않고 새 랏만 연다.
- 서버는 같은 `(agent_id, lot_id)` 조합을 두 번 닫지 않는다.

---

## 4. `VISION_ALARM`

```json
{
  "type": "VISION_ALARM",
  "eventUid": "3f2a91c4e07b5d18a6420f9b31cc77e5",
  "sourceDrive": "E",
  "sourceFile": "E:\\VisionPC\\LOG\\260825.Status.log",
  "code": "12",
  "name": "Cylinder Timeout",
  "detail": "CYL-3",
  "rawMessage": "12. Cylinder Timeout(CYL-3)",
  "rawLine": "[2026/08/25 14:03:11.100][Alarm] 12. Cylinder Timeout(CYL-3)",
  "alarmAt": "2026-08-25T14:03:11.100+09:00",
  "alarmAtRaw": "2026/08/25 14:03:11.100"
}
```

- 알람에는 `unitSeq`가 없으므로 **Agent가 `eventUid`를 직접 만든다**:
  `MD5(agentId + "|" + sourceFile + "|" + byteOffset + "|" + rawLine)` 의 32자 hex.
  바이트 오프셋이 들어가므로 같은 문구의 알람이 반복돼도 각각 별개로 남는다.
- Example A은 한 로그 파일을 두 agent가 공유한다. 같은 줄을 두 `agentId`로 각각
  보내며, `eventUid`에 `agentId`가 들어가므로 서로 다른 두 건으로 저장된다.
- `alarmAt` 파싱에 실패하면 `null`로 보내고 `alarmAtRaw`는 그대로 채운다.

---

## 5. 하트비트 (UDP)

```
UDP → <server>:6002
{ "agentId": "C-2_EXAMPLE_C", "line": "C-2", "visionKey": "EXAMPLE_C", "agentVersion": "2.0.0", "ts": "2026-08-25T14:03:12+09:00" }
```

- 기본 2초 간격. 유실돼도 무방하므로 UDP.
- Example A처럼 한 프로세스가 두 agent를 담당하면 **agent마다 별도 패킷**을 보낸다.
  안 그러면 한쪽이 영원히 OFFLINE으로 보인다.
- 하트비트만으로도 `agents` 행이 생성된다 (배포는 됐지만 아직 생산이 없는 상태를
  N/D가 아니라 IDLE로 보이게 하기 위해).

---

## 6. Agent가 지켜야 할 것

- **전송에 실패하면 오프셋을 전진시키지 않는다.** 재전송은 서버가 걸러내므로 안전하다.
- **파일 오프셋과 랏 상태를 매 폴링마다 통째로 다시 쓰지 않는다.** 변경이 있을 때만
  저장하고, 오래된 파일의 상태는 정리한다 (이전 구현은 1초마다 전체 상태 파일을
  다시 썼고 항목이 날짜별로 무한히 쌓였다).
- **자정 경계에서 전날 파일을 최소 한 폴링 주기 더 읽는다.** 날짜 패턴만 보고 즉시
  전환하면 00:00 직후에 전날 파일로 들어온 마지막 행들이 영원히 유실된다.
