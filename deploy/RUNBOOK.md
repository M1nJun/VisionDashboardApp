# 설치 런북

개발 PC에서 만든 것을 실제 공장에 올리는 절차. 위에서부터 순서대로 하면 된다.

- **A** 개발 PC에서 패키지 만들기
- **B** 중앙 PC 설치
- **C** 검사 PC 사전 설정 (검사 PC마다 한 번)
- **D** Agent 배포
- **E** 확인
- **F** 문제가 생겼을 때

---

## A. 개발 PC — 패키지 만들기

```powershell
cd "<repo>\web"
npm install
npm run build

cd "..\server"
$env:PATH = "C:\Tools\apache-maven-3.9.16\bin;$env:PATH"
mvn -o -DskipTests package

cd "..\agent"
dotnet publish -c Release

cd "..\deploy"
.\Package-Central.ps1
```

`dist\central-pc\` 가 만들어진다 (약 90MB). **이 폴더 하나만** 중앙 PC로 옮기면 된다.

```
dist\central-pc\
  server.jar                        서버 + 대시보드 SPA + 카탈로그 (전부 이 안에)
  run-server.cmd                    ← 여기 DB 비밀번호를 적는다
  Install-Service.ps1               부팅 시 자동 시작 등록
  db\schema.sql                     DB 스키마 (한 번 적용)
  db\smoke-test.sql                 스키마 검증용 (선택)
  contracts\                        카탈로그·토폴로지 참고본
  deploy-agents\                    검사 PC 배포 도구 (D단계에서 사용)
    Deploy-Agent.ps1
    deploy.json                     ← 중앙 PC IP를 적는다
    VisionAgent.exe
    contracts\
```

USB나 파일 공유로 옮긴다. 공장망은 오프라인이므로 인터넷에서 받아야 하는 것은 없다.

---

## B. 중앙 PC 설치

### B-1. 사전 준비물 확인

| 필요한 것 | 확인 방법 |
|---|---|
| **Java 21** (JRE로 충분) | `java -version` → `21.x` |
| **MySQL 8.4** | `mysql --version` |

Java가 없으면 기존 `VisionHub\DashboardServer\central-pc-install\OpenJDK21U-jre_x64_windows_hotspot_21.0.12_8.msi` 를 설치하면 된다. `run-server.cmd` 가 `JAVA_HOME` → `C:\Program Files\Eclipse Adoptium\` → `PATH` 순으로 알아서 찾는다.

### B-2. 폴더 배치

`dist\central-pc\` 를 아래로 복사:

```
C:\VisionDashboard\
```

### B-3. 데이터베이스 만들기

**기존 `vision_dashboard` DB는 건드리지 않는다.** 새 DB를 따로 만든다.

> **PowerShell 주의 두 가지.** `mysql` 은 보통 PATH에 없으므로 전체 경로로
> 부른다. 그리고 PowerShell은 `<` 입력 리다이렉션을 지원하지 않으므로
> 스키마 파일은 파이프로 넣는다.

mysql 위치 확인:

```powershell
Get-ChildItem "C:\Program Files\MySQL" -Filter mysql.exe -Recurse | Select-Object -First 1 -ExpandProperty FullName
```

```powershell
cd C:\VisionDashboard
$mysql = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"   # 위에서 나온 경로

& $mysql -u root -p -e "CREATE DATABASE visiondash CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

& $mysql -u root -p -e "CREATE USER IF NOT EXISTS 'vision_app'@'localhost' IDENTIFIED BY '여기에_비밀번호';"

& $mysql -u root -p -e "GRANT ALL PRIVILEGES ON visiondash.* TO 'vision_app'@'localhost'; FLUSH PRIVILEGES;"

Get-Content db\schema.sql -Raw | & $mysql -u root -p visiondash
```

줄마다 root 비밀번호를 묻는다. 스키마가 제대로 들어갔는지 (선택):

```powershell
Get-Content db\smoke-test.sql -Raw | & $mysql -u root -p --table visiondash
```

`PASS` 만 나와야 한다.

### B-4. 설정 적기

`C:\VisionDashboard\run-server.cmd` 를 메모장으로 열고 위쪽만 고친다:

```bat
set DB_PASSWORD=여기에_B-3에서_정한_비밀번호
```

포트를 바꿀 일이 없으면 나머지는 그대로 둔다.

### B-5. 이미지 캐시 폴더

기본값은 `D:\VisionDashboardImages` 다. **중앙 PC에 D 드라이브가 없으면** 서버를 띄운 뒤 대시보드의 **Settings → Images → `image_local_root`** 에서 바꾼다 (예: `C:\VisionDashboardImages`). 폴더는 서버가 알아서 만든다.

용량은 90일치 불량 이미지 기준으로 잡는다. 최근 24시간분만 미리 받아두고 나머지는 조회할 때 받으므로 예전 방식보다 훨씬 적게 쓴다.

### B-6. 방화벽 열기

**이걸 빠뜨리면 Agent가 아무것도 보내지 못한다.** 관리자 PowerShell에서:

```powershell
New-NetFirewallRule -DisplayName "Vision Dashboard HTTP" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
New-NetFirewallRule -DisplayName "Vision Dashboard Heartbeat" -Direction Inbound -Protocol UDP -LocalPort 6002 -Action Allow
```

- **8080/TCP** — 운영자 브라우저 + **모든 검사 PC가 이벤트를 POST**
- **6002/UDP** — 검사 PC 하트비트

### B-7. 처음 한 번은 손으로 띄워본다

```cmd
cd C:\VisionDashboard
run-server.cmd
```

콘솔에 이 네 줄이 나와야 한다:

```
Schema check: all 14 tables present in 'visiondash'
Settings loaded: 21 keys
Catalog loaded: 7 vision types, 9 lines, 54 PCs; production reference order [EXAMPLE_C, EXAMPLE_B_CATHODE]
Heartbeat listener on UDP :6002
```

브라우저로 `http://localhost:8080/dashboard/` 를 열면 **63칸이 전부 `N/D`** 인 그리드가 나온다. Agent를 아직 안 깔았으니 정상이다.

`Ctrl+C` 로 끈다.

> `Database 'visiondash' is missing N of the 14 tables` 가 나오면 B-3의 `schema.sql` 적용을 건너뛴 것이다.

### B-8. 부팅 시 자동 시작 등록

**관리자 PowerShell**에서:

```powershell
cd C:\VisionDashboard
.\Install-Service.ps1
```

작업 스케줄러에 `VisionDashboardServer` 작업이 등록되고 바로 시작된다. 이후 PC를 재부팅해도 자동으로 올라오고, 죽으면 1분 뒤 다시 뜬다.

로그: `C:\VisionDashboard\logs\server.log` (20MB씩 14개 순환)

끄고 켜기:

```powershell
Stop-ScheduledTask  -TaskName VisionDashboardServer
Start-ScheduledTask -TaskName VisionDashboardServer
Get-ScheduledTask   -TaskName VisionDashboardServer | Select-Object State
```

---

## C. 검사 PC 사전 설정 (검사 PC마다 한 번)

**이건 중앙에서 원격으로 못 한다.** 각 검사 PC에서 직접 하거나 그룹 정책으로 밀어야 한다. 예전 시스템에서 이미 해둔 PC는 다시 할 필요 없다.

### C-1. 로컬 관리자 계정

각 검사 PC에 **같은 이름·같은 비밀번호**의 로컬 관리자 계정이 있어야 한다. 기본값은 `VisionDeploy` (`deploy.json` 의 `deployAccount`).

```cmd
net user VisionDeploy 비밀번호 /add
net localgroup Administrators VisionDeploy /add
wmic useraccount where "name='VisionDeploy'" set PasswordExpires=false
```

### C-2. LocalAccountTokenFilterPolicy

**이게 없으면 원격 서비스 제어가 무조건 거부된다.** 로컬 관리자 계정으로 원격 접속하면 Windows가 관리자 토큰을 벗겨버리기 때문이다.

```cmd
reg add HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v LocalAccountTokenFilterPolicy /t REG_DWORD /d 1 /f
```

재부팅은 필요 없다.

### C-3. 방화벽 — 중앙 PC에서 들어오는 SMB 허용

```powershell
Enable-NetFirewallRule -DisplayGroup "File and Printer Sharing"
```

배포(파일 복사 + `sc.exe`)와 **불량 이미지 가져오기**가 모두 이 경로를 쓴다.

### C-4. 이미지 드라이브 공유 확인

중앙 서버는 `\\<검사PC IP>\F\Files\Image\...` 형태로 이미지를 읽는다. **드라이브 문자와 같은 이름**의 공유가 있어야 한다.

```cmd
net share
```

`F  F:\` 같은 줄이 보여야 한다. 없으면:

```cmd
net share F=F:\ /grant:Everyone,READ
```

CSV에 적히는 이미지 경로의 드라이브(보통 `F`, 웰딩은 `E`/`F`/`G`)를 전부 공유해야 한다. 예전 시스템에서 이미 공유돼 있으면 그대로 두면 된다.

### C-5. 중앙 PC로 나가는 통신 확인

검사 PC에서:

```cmd
ping 10.0.0.10
powershell -c "Test-NetConnection 10.0.0.10 -Port 8080"
```

`TcpTestSucceeded : True` 가 나와야 한다.

---

## D. Agent 배포

**중앙 PC에서** 실행한다 (검사 PC망에 있고 SMB·`sc.exe` 접근이 되는 곳).

### D-1. 중앙 PC 주소 확인

`C:\VisionDashboard\deploy-agents\deploy.json` 을 열고:

```json
{
  "centralHost": "10.0.0.10",   ← 중앙 PC의 실제 IP
  "serverPort": 8080,
  "modelToken": "MDL"               ← 웰딩 CSV 파일명에 들어가는 모델 토큰
}
```

`centralHost` 가 틀리면 Agent가 아무 데도 못 보낸다.

### D-2. 먼저 한 대만

```powershell
cd C:\VisionDashboard\deploy-agents

# 무엇이 어떤 설정을 받을지 먼저 눈으로 확인
.\Deploy-Agent.ps1 -Line C-2 -VisionKey EXAMPLE_C -DryRun

# 실제 배포 (검사 PC 로컬 관리자 자격증명을 물어본다)
.\Deploy-Agent.ps1 -Line C-2 -VisionKey EXAMPLE_C
```

결과 표의 `After` 가 `RUNNING` 이어야 한다.

브라우저에서 대시보드를 열고 **C-2 호기의 `EXAMPLE_C` 칸이 `N/D` 에서 살아나는지** 확인한다. 생산 중이면 몇 초 안에 숫자가 오른다.

### D-3. 한 호기 전체

```powershell
.\Deploy-Agent.ps1 -Line C-2
```

### D-4. 전체

```powershell
.\Deploy-Agent.ps1
```

54대에 순차로 배포된다. 마지막에 실패한 PC와 **"이 PC들은 아무것도 수집하지 않고 있습니다"** 목록이 나온다.

---

## E. 확인

### E-1. Agent 상태 한눈에

```powershell
cd C:\VisionDashboard\deploy-agents
.\Deploy-Agent.ps1 -Status
```

54대가 전부 `RUNNING` 이어야 한다.

### E-2. 대시보드

`http://<중앙PC IP>:8080/dashboard/`

| 봐야 할 것 | 정상 |
|---|---|
| 우상단 시계 | 초가 흐른다 (화면이 멈춘 게 아님) |
| Inspectors | `63 / 63`, OFF 0 |
| Total Output | 호기별 리드비전 합. **전체 검사량 총합이 아니다** |
| Line Output | 각 호기에 `EXAMPLE_C` 표시. `~EXB-C` 는 리드비전이 죽어서 대체 중이라는 뜻 |
| 셀 | 정상은 차분한 초록 테두리, 이상만 노랑/빨강 네온 |
| 셀 클릭 | 상세 페이지에서 불량 이미지가 뜬다 |

### E-3. 이미지가 실제로 들어오는지

불량이 있는 셀을 클릭 → Defect Images. 처음엔 `Fetching from the inspection PC…` 였다가 잠시 뒤 사진이 뜬다.

DB로 직접 볼 수도 있다:

```powershell
& $mysql -u root -p visiondash -e "SELECT state, COUNT(*) FROM defect_images GROUP BY state;"
```

`ready` 가 늘고 있으면 정상이다.

### E-4. 서버 로그

```cmd
type C:\VisionDashboard\logs\server.log
```

`images: N fetched` 가 주기적으로 찍히면 이미지 워커가 도는 것이다.

---

## F. 문제가 생겼을 때

### 셀이 전부 `N/D`

Agent가 하나도 안 붙은 것이다.

1. `.\Deploy-Agent.ps1 -Status` → `RUNNING` 인가
2. 검사 PC의 `C:\VisionDashboardAgent\agent.log` 를 본다 (`\\<IP>\C\VisionDashboardAgent\agent.log`)
3. `send failed` 가 보이면 → 중앙 PC 방화벽(B-6) 또는 `deploy.json` 의 `centralHost`

### 셀이 회색(OFFLINE)인데 Agent는 RUNNING

하트비트(6002/UDP)가 안 닿는 것이다. B-6의 UDP 규칙을 확인한다. TCP만 열고 UDP를 빠뜨리면 **이벤트는 들어오는데 상태만 계속 오프라인**으로 보인다.

### 불량 이미지가 안 뜬다

```powershell
& $mysql -u root -p visiondash -e "SELECT state, last_error, COUNT(*) FROM defect_images GROUP BY state, last_error LIMIT 10;"
```

| `last_error` | 원인 |
|---|---|
| `port 445 unreachable` | 검사 PC 방화벽 (C-3) |
| `not on the share yet` | 정상. 비전이 CSV를 이미지보다 먼저 쓴 것이고 곧 다시 받는다 |
| `no PC hosts <line>/<key>` | `topology.json` 에 그 슬롯이 없다. 고치면 15분 안에 저절로 복구된다 |
| `NoSuchFileException` 반복 | C-4의 드라이브 공유가 없다 |

### 배포가 `UNREACHABLE` 로 실패

C-1(로컬 관리자 계정)과 C-2(`LocalAccountTokenFilterPolicy`)를 확인한다. 거의 항상 둘 중 하나다.

### 서버가 안 뜬다

`C:\VisionDashboard\logs\server.log` 의 마지막 30줄을 본다.

| 메시지 | 조치 |
|---|---|
| `missing N of the 14 tables` | B-3의 `schema.sql` 적용 |
| `Access denied for user 'vision_app'` | `run-server.cmd` 의 `DB_PASSWORD` |
| `vision catalog / topology mismatch` | `contracts\` 파일이 손상됐다. 패키지에서 다시 복사 |
| `Address already in use` | 8080을 다른 프로그램이 쓴다. 예전 DashboardServer가 아직 떠 있지 않은지 확인 |

### 예전 시스템과 같이 돌려도 되나

된다. DB가 다르고(`visiondash` vs `vision_dashboard`) 포트만 겹치지 않게 하면 된다. 예전 시스템은 8080을 쓰므로, **같이 돌리려면** `run-server.cmd` 의 `SERVER_PORT` 와 `deploy.json` 의 `serverPort` 를 함께 바꾼다 (예: 8090). 다만 검사 PC의 Agent는 한쪽만 붙을 수 있으니, 배포한 PC는 새 시스템에만 데이터를 보낸다.

---

## 업데이트할 때

```powershell
# 개발 PC - 프론트엔드부터 다시 빌드한다. jar 안에 화면이 같이 들어간다.
cd web; npm run build
Remove-Item server	arget\classes\static -Recurse -Force -ErrorAction Ignore
cd ..\server; mvn -o -DskipTests package
cd ..\deploy; .\Package-Central.ps1

# 중앙 PC
Stop-ScheduledTask -TaskName VisionDashboardServer

# db\schema.sql 을 항상 다시 적용한다. CREATE TABLE IF NOT EXISTS + INSERT IGNORE 로만
# 되어 있어서 여러 번 돌려도 안전하고, 새로 생긴 설정 항목은 이때 들어온다.
$mysql = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"
Get-Content db\schema.sql -Raw | & $mysql -u root -p visiondash

# dist\central-pc\server.jar 를 C:\VisionDashboard\server.jar 로 덮어쓴다
Start-ScheduledTask -TaskName VisionDashboardServer

# Agent도 바뀌었으면
cd C:\VisionDashboard\deploy-agents
.\Deploy-Agent.ps1
```

`run-server.cmd` 와 `deploy.json` 은 덮어쓰지 않는다 — 비밀번호와 IP가 들어 있다.
`contracts\` 는 **두 군데** 다 바꾼다: `C:\VisionDashboard\contracts\` 와
`C:\VisionDashboard\deploy-agents\contracts\`.

### 화면이 예전 그대로일 때

대시보드는 카탈로그를 **페이지를 열 때 딱 한 번** 읽는다. 그래서 화면을 띄워둔 채로
서버만 새로 올리면, 그리드 숫자는 새 서버에서 오는데 호기 목록·패널 구성은 예전
것이 그대로 남는다. 에러가 나지 않으니 겉보기엔 "적용이 안 된" 것처럼 보인다.

이제는 서버가 `/api/build` 로 지문을 내려주고, 열려 있는 화면이 30초마다 확인해서
지문이 바뀌면 **스스로 새로고침한다.** 벽에 걸어둔 화면도 배포 후 30초 안에 알아서
따라온다.

다만 이 기능이 없던 예전 화면이 떠 있다면 그 화면은 스스로 못 고친다. 배포 후 한
번만 브라우저에서 **Ctrl + Shift + R** (강력 새로고침) 을 눌러준다. 그 다음부터는
필요 없다.

확인:

```powershell
# 서버가 들고 있는 지문
curl.exe http://localhost:8080/dashboard/api/build
# 로그에도 찍힌다
Select-String "Build fingerprint" C:\VisionDashboard\logs\server.log
```

Agent를 다시 배포해도 생산량과 불량률은 0으로 돌아가지 않는다. 새로 깔린 Agent는
`state.json` 이 없는 상태로 깨어나지만, 오늘 CSV를 처음부터 읽지 않고 **지금 돌고 있는
랏이 시작되는 지점**을 찾아 거기서부터 읽는다. 서버도 이미 끝난 랏으로 되돌아가는
요청은 거부하므로, 살아 있는 랏의 카운터가 지워지지 않는다.

### 생산 목표 바꾸기

대시보드 Settings 화면에서 바꾼다. 서버를 다시 띄울 필요는 없다.

| 설정 | 기본값 | 뜻 |
|---|---|---|
| `daily_target_cells` | 100000 | 공정 전체 목표 (상단 Production 타일) |
| `daily_target_cells_per_line` | 14286 | 호기 한 대의 목표 (Line Output 진행바) |

호기별 목표는 전체 목표를 호기 수로 나눈 값이 아니라 따로 적어 둔 값이다. 관리 대상
7개 호기 × 17,143 = 120,001 이므로 지금은 두 값이 맞아떨어진다. **라인을 늘리거나
줄이면 두 값을 같이 손봐야 한다.**

대시보드의 생산량·불량률·DLNG률은 전부 **현재 랏 기준**이다. 랏이 바뀌면 0에서 다시
시작한다. 랏을 넘어선 누계가 필요하면 상세 페이지의 Lot History를 본다.

### 관리 대상 호기 바꾸기

`contracts/topology.json` 의 `lines` 와 `pcs` 를 고친다. 여기에 없는 호기는 그리드에
칸이 생기지 않고, Agent도 배포되지 않으며, 혹시 남아 있는 Agent가 보고를 해도 서버가
400으로 거부한다. 고친 뒤 `python contracts/validate.py` 로 확인하고, 중앙 PC의
`contracts\topology.json` 과 `deploy-agents\contracts\topology.json` 둘 다 바꾼 뒤
서버를 다시 띄운다.

2-1, 2-2 호기는 이 대시보드의 관리 대상이 아니라서 빠져 있다. 예전에 그 PC들에 Agent를
깔아 둔 적이 있다면 서비스를 지워 두는 편이 낫다 — 놔둬도 데이터가 섞이지는 않지만
계속 거부당하는 요청을 보내며 서버 로그를 채운다.

```powershell
sc.exe \\10.93.176.31 stop VisionAgent
sc.exe \\10.93.176.31 delete VisionAgent
```
