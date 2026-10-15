# 갤럭시 초기화 — 폰 단독 실행 앱 요구사항

> 이 문서는 **구현 개발자에게 넘기는 요구사항 명세**입니다.
> "무엇을 만들어야 하는가"와 "각 단계가 실제로 어떤 명령을 보내는가"를 담습니다.
> 동작 기준(원본)은 PC 도구 `V6.py`의 `build_pipeline()`입니다.

---

## 1. 목적과 범위

- forholiday 대여폰을 다음 손님에게 넘기기 전, **이전 사용자 데이터를 지우고 기본 상태로 되돌리는 작업**을 자동화한다.
- 기존에는 PC + USB로 `V6.py`를 돌렸다. 이 앱은 **PC 없이 폰 한 대만으로** 같은 19단계를 실행한다.
- 내부 운영 전용. 매장 직원이 사용.

## 2. 전제 · 제약

| 항목 | 내용 |
|---|---|
| 권한 통로 | **Shizuku** 사용. 앱은 Shizuku가 제공하는 shell(uid 2000) 권한으로 명령 실행. 권한 획득 로직을 앱에 자체 구현하지 않는다. |
| 네트워크 | 현장 Wi-Fi 있음(Shizuku 무선 디버깅 기동용). |
| 처리 단위 | **한 대씩**. PC 도구와 달리 동시 처리 없음. |
| 잠금 | PIN·잠금 화면 없음. |
| 실행 트리거 | [초기화 시작] → **데이터 삭제 확인 팝업** → [모두 지우기]로만 시작. |
| 최소 버전 | Android 11(무선 디버깅 지원) 이상 권장. |
| 대상 기기 | 갤럭시 S23 ~ S26. |

### 시리즈 판정 (`V6.py` `series_from_model`)
모델명으로 판정하고, 매칭 없으면 **S24**로 간주.

| 모델명 포함 | 시리즈 |
|---|---|
| `S91` | S23 |
| `S92` | S24 |
| `S93` | S25 |
| `S94` | S26 |

예: `SM-S948N` → `S94` → **S26** → 배경화면 `S26.png`

## 3. 화면 흐름

디자인 완성본(캔버스): https://claude.ai/artifact/Lz3XJyubjeYkbLjkXsaEym

0. **자동 연결** — Wi-Fi → 무선 디버깅 → 연결 → 권한 확인을 자동 진행. 안 되면 안내.
1. **시작** — 기기 정보(이름·모델·시리즈), 언어 선택(라디오 버튼), [초기화 시작].
2. **데이터 삭제 확인 팝업** — "이 폰의 모든 데이터가 지워져요 / 되돌릴 수 없어요" + 지워지는 항목, [취소] / 빨간 [모두 지우기].
3. **진행 중** — 전체 %·현재 단계, 19단계 기록을 **고정 높이 상자 안에서 내부 스크롤**(현재 단계 자동 추적). 알림창에도 진행률.
4. **완료** — 남은 계정·언어·필수 앱·배경화면 요약, [전원 끄기].
5. **일부 실패** — 실패 단계(다시 시도) / 수동 처리(e-SIM 등) 구분 표시.

### UI 규칙
- 버튼은 **아이콘 + 글자** 함께(아이콘만 두지 않음).
- 직원용 언어 표기는 **한글 이름**: `일본어 (보조: 한국어, 영어)`.
- 언어 선택 목록 표기(원어 병기)는 아래 §4-1 표 그대로.

## 4. 초기화 19단계

- 모든 명령은 Shizuku shell(uid 2000)로 실행.
- `.dex` 헬퍼는 `/data/local/tmp/`로 복사 후 `app_process`로 실행(아래 공통 패턴).
- **best-effort 원칙**: 개별 명령이 실패해도 멈추지 않고 다음으로 진행. 단, 연결 자체가 끊기면 그 단계에서 중단하고 "여기서부터 다시 시도"를 제공.

### 공통: DEX 실행 패턴 (`push_dex_if_needed` + `app_process`)
```
push <dex>  →  /data/local/tmp/<dex>
ls /data/local/tmp/<dex>          # 검증, 실패 시 1회 재푸시
CLASSPATH=/data/local/tmp/<dex> app_process /system/bin <MainClass> [args...]
```

---

### 4-1. 언어 설정 — `locale_changer.dex`
선택 언어를 맨 앞에, 나머지 기본 언어(한/영/일)를 보조로 설정.
```
locale 목록 = [선택] + ([ko-KR, en-US, ja-JP] 중 선택과 다른 것)
app_process ... LocaleChanger <locale1> <locale2> ...
성공 판정: stdout에 "SUCCESS"
```
선택지(원어 병기 / 한글):

| locale | 목록 표기 | 한글 |
|---|---|---|
| ja-JP | 日本語 (일본어) | 일본어 |
| en-US | English (영어) | 영어 |
| ko-KR | 한국어 | 한국어 |
| zh-CN | 中文简体 (중국어 간체) | 중국어 간체 |
| zh-TW | 中文繁體 (중국어 번체) | 중국어 번체 |

### 4-2. 계정 삭제 — `account_remover.dex`
삼성 계정만 보존, 나머지 전부 삭제.
```
dumpsys account                                  # 삭제 전 현황
app_process ... AccountRemover                   # IAccountManager.removeAccountAsUser
dumpsys account                                  # 삭제 후 재확인
```
- 보존 대상 타입: `com.osp.app.signin`, `com.samsung.android.mobileservice`, `com.samsung`(prefix)
- 재확인 후 비삼성 계정이 남으면 **실패로 표시**(기기/OS에 따라 삭제가 거부될 수 있음).

### 4-3. e-SIM 프로필 삭제 — `esim_manager.dex`
```
app_process ... EsimManager delete-all
```
- 결과 파싱: `SUCCESS:NO_ESIM`(없음) / `SUCCESS:ALL_ESIM_DELETED`(완료) / `FAIL:ESIM_DELETE`·`FAIL:ALL_ESIM_DELETE_FAILED`(실패)
- **삭제 실패 시**: `am start -a android.telephony.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS`로 SIM 관리자를 열고, **수동 삭제 필요**로 안내(화면 5).

### 4-4. Google 앱 기록 삭제 — `pm clear`
대상 패키지:
```
com.google.android.googlequicksearchbox   (Google 검색)
com.android.chrome                        (Chrome)
com.google.android.youtube                (YouTube)
com.google.android.gm                     (Gmail)
com.google.android.apps.maps              (지도)
com.google.android.apps.docs             (드라이브)
com.google.android.calendar               (캘린더)
com.google.android.apps.photos            (포토)
com.sec.android.app.sbrowser              (삼성 인터넷)
```

### 4-5. 사용자 설치 앱 삭제
```
pm list packages --user 0 -3               # 사용자 설치 앱 목록
pm uninstall --user 0 <app>                # 제외목록 외 전부
```
제외목록(보존):
```
com.sec.android.app.popupcalculator
com.nhn.android.nmap
com.alphainventor.filemanager
com.forholiday.galaxyreset        ← 초기화 앱 자신(지우면 다음 초기화 불가)
```

### 4-6. 내부 저장소 초기화
```
rm -rf /storage/emulated/0/*
rm -rf /storage/emulated/0/.[!.]* /storage/emulated/0/..?*    # 숨김 파일('..' 제외)
```

### 4-7. 로그·캐시 정리
먼저 통화/SMS/주소록 — `content_cleaner.dex`:
```
app_process ... ContentCleaner
결과에 PERMISSION_DENIED:<항목> 이면 해당 항목 pm clear 폴백:
  CALL_LOG/CONTACTS → pm clear com.android.providers.contacts
  SMS               → pm clear com.android.providers.telephony
이어서 pm clear:
  com.samsung.android.messaging          (삼성 메시지, 임시저장 포함)
  com.samsung.android.providers.contacts
  com.samsung.android.app.contacts
  com.samsung.android.dsms
  com.android.mms.service
```
그다음 `pm clear`:
```
com.sec.android.gallery3d, com.nhn.android.nmap, com.sec.android.themestore,
com.sec.android.app.vepreload, com.samsung.android.app.clipboardedge,
com.samsung.android.honeyboard
```

### 4-8. 갤러리 휴지통 정리
```
am force-stop com.sec.android.gallery3d; am force-stop com.sec.android.app.myfiles
rm -rf <휴지통/썸네일 경로 목록>      # 아래
pm clear <미디어 provider 목록>       # 아래
am broadcast -a android.intent.action.MEDIA_MOUNTED -d file:///sdcard
  └ SecurityException이면 폴백: am force-stop providers.media / google...media.module
```
휴지통/썸네일 경로:
```
/sdcard/Android/data/com.sec.android.gallery3d/files/Trash , /.Trash , /cache
/sdcard/Android/data/com.sec.android.app.myfiles/files/Trash , /.Trash
/sdcard/Android/data/com.sec.android.app.vepreload/files/Trash
/storage/emulated/0/Android/.Trash
/sdcard/.Trash , /sdcard/.Trash-0 , /sdcard/Recycle
/sdcard/{DCIM,Pictures,Music,Movies,Download}/.thumbnails
/sdcard/Android/data/com.sec.android.gallery3d/files/.thumbnails
/sdcard/Android/data/{com.android.chrome,dji.mimo,com.nhn.android.nmap}/cache
```
미디어 provider:
```
com.sec.android.gallery3d, com.sec.android.app.myfiles,
com.samsung.android.providers.media, com.samsung.android.providers.trash,
com.android.providers.media, com.google.android.providers.media.module
```

### 4-9. 미디어 스토어 갱신
```
content delete --uri content://media/external/images/media
content delete --uri content://media/external/video/media
content delete --uri content://media/external/audio/media
content delete --uri content://media/external/file
```

### 4-10. 썸네일 잔여물 제거
```
rm -rf /sdcard/{DCIM,Pictures,Music,Movies,Download}/.thumbnails
```

### 4-11. 카메라 설정 초기화
```
pm clear com.sec.android.app.camera
pm clear com.samsung.android.app.cameraassistant
```

### 4-12. 내비게이션 바 초기화
```
cmd overlay disable --user 0 <navbar overlay 목록>
settings put <아래 값들>
```
overlay 목록:
```
com.samsung.internal.systemui.navbar.sec_gestural
com.samsung.internal.systemui.navbar.sec_gestural_no_hint
com.samsung.internal.systemui.navbar.gestural_no_hint
com.android.internal.systemui.navbar.gestural
com.android.internal.systemui.navbar.transparent
com.android.internal.systemui.navbar.threebutton
```
settings:
```
global navigation_bar_gesture_while_hidden 0
secure navigation_mode 0
global navigationbar_key_order 0
secure navigationbar_key_order 0
global navigation_bar_gesture_hint 1
```

### 4-13. 글자 크기 초기화
```
system font_scale 1.0
system device_font_scale 1.0
global font_size 2
global bold_text 0
global font_style_index 0
secure enhanced_comfort_font_value 0
```

### 4-14. 홈 독 앱 배치
각 패키지에 대해:
```
cmd package install-existing <pkg>    # 시스템 APK 재연결 (NameNotFoundException이면 건너뜀)
pm enable <pkg>
```
대상: `com.samsung.android.dialer`(전화), `com.samsung.android.messaging`(메시지), `com.sec.android.app.sbrowser`(삼성 인터넷), `com.sec.android.app.camera`(카메라)

### 4-15. 런처 초기화
```
pm clear com.sec.android.app.launcher
am start -n com.sec.android.app.launcher/.activities.LauncherActivity
dumpsys appwidget                                   # 바인딩된 위젯 provider 추출
pm disable-user --user 0 <provider>                 # 탐지된 provider 전부
am force-stop launcher; am start -n ...LauncherActivity; input keyevent KEYCODE_HOME
pm enable <provider>                                # 재활성화 (반드시)
```
- `dumpsys appwidget`의 `Widgets:` 구역에서만 `cmp:ComponentInfo{<pkg>/...}`의 `<pkg>` 추출(`Hosts:`·`Grants:` 구역 제외).
- 중간 실패와 무관하게 provider는 **반드시 재활성화**(finally).

### 4-16. Galaxy Store 복원 차단
```
pm clear com.sec.android.app.samsungapps
pm clear com.samsung.android.scloud
pm disable-user --user 0 com.sec.android.app.samsungapps
pm disable-user --user 0 com.samsung.android.scloud
am force-stop launcher; am start -n ...LauncherActivity
pm enable <두 패키지>                                # 반드시 재활성화
```

### 4-17. 배경화면 적용 — `wallpaper_setter.dex`
```
mkdir -p /sdcard/DCIM/ForHoliday
push <시리즈>.png → /sdcard/DCIM/ForHoliday/<시리즈>.png
am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://<remote>
# 라이브 배경 오버레이 정리
for pkg in [wallpaper.live, dynamiclock, app.dressroom]: am force-stop pkg
for pkg in 위 목록: pm clear pkg
app_process ... WallpaperSetter <remote>            # 홈+잠금 설정
dumpsys wallpaper                                   # 검증: 라이브 배경이 덮었으면 재정리 후 재설정
```

### 4-18. 필수 앱 설치
```
pm list packages                                    # 설치 여부 확인
pm install -r <apk>                                 # 없는 것만
pm grant com.forholiday.galaxyreset android.permission.WRITE_SECURE_SETTINGS
```
대상: `com.nhn.android.nmap`(nmap.apk), `com.alphainventor.filemanager`(filemanager.apk), `com.forholiday.galaxyreset`(초기화 앱)

### 4-19. 최근 앱 목록 제거 — `recent_tasks_cleaner.dex`
```
app_process ... RecentTasksCleaner
성공 판정: stdout에 "SUCCESS"
```

### 종료
완료 후 [전원 끄기]:
```
reboot -p
```

## 5. 동작 요구사항 (중요)

- **포그라운드 서비스에서 실행**: 1·13단계(언어·글꼴)가 시스템 설정을 바꿔도 화면이 재생성되지 않게 하고, 15·19단계(런처·홈키·최근앱)에서 앱이 뒤로 밀려도 초기화가 끊기지 않게 한다. 끝나면 앱을 다시 앞으로.
- **진행 상태**: 현재 단계/완료/경고(수동 처리)를 화면과 알림창에 표시.
- **중단·재시도**: 연결이 끊기면 그 단계에서 멈추고 "여기서부터 다시 시도". [모두 지우기] 중복 실행은 무시.
- **2·3단계는 실패 가능**: 권한을 얻어도 기기/OS에 따라 계정·e-SIM 삭제가 거부될 수 있음 → 폴백·수동 안내 유지(V6.py와 동일).
- **자기 보호**: 초기화 앱은 5단계 제외목록과 18단계 설치목록 **양쪽**에 포함.
- **Shizuku 관리**: 재부팅 시 꺼짐. 손님에게 넘어간 뒤 꺼져 있도록 관리.

## 6. 재사용 자산 (`galaxy-reset-tool/` 폴더)

앱 assets에 넣고 실행 시 `/data/local/tmp/` 등으로 복사해 그대로 사용.

| 파일 | 용도 |
|---|---|
| `locale_changer.dex` | 1단계 언어 |
| `account_remover.dex` | 2단계 계정 |
| `esim_manager.dex` | 3단계 e-SIM |
| `content_cleaner.dex` | 7단계 통화/SMS/주소록 |
| `wallpaper_setter.dex` | 17단계 배경 |
| `recent_tasks_cleaner.dex` | 19단계 최근 앱 |
| `S23.png ~ S26.png` | 17단계 배경 이미지(시리즈별) |
| `nmap.apk`, `filemanager.apk` | 18단계 필수 앱 |
| `reset_icon.ico` | 앱 아이콘 |

> 참고: `.dex`의 각 MainClass(`LocaleChanger`, `AccountRemover`, `EsimManager`, `ContentCleaner`, `WallpaperSetter`, `RecentTasksCleaner`)와 입출력 규약은 같은 폴더의 `*.java` 소스 참조.

## 7. 원본 대조

각 단계의 정확한 동작·예외 처리·대기 시간은 `V6.py`의 대응 함수를 최종 기준으로 삼는다.

| 단계 | V6.py 함수 |
|---|---|
| 1 | `set_device_language` |
| 2 | `remove_non_samsung_accounts` |
| 3 | `delete_esim_profiles` |
| 4 | `clear_google_apps_history` |
| 5 | `delete_user_installed_apps` |
| 6 | `wipe_internal_storage` |
| 7 | `clear_logs_and_cache` / `clear_call_sms_contacts` |
| 8 | `deep_clean_gallery_trash` |
| 9 | `clear_media_store` |
| 10 | `build_pipeline` 내 `clear_thumbnails` |
| 11 | `reset_camera_settings` |
| 12 | `reset_navigation_bar` |
| 13 | `reset_font_settings` |
| 14 | `ensure_hotseat_apps` |
| 15 | `reset_launcher_with_clean_home` |
| 16 | `block_galaxy_store_auto_restore` |
| 17 | `push_default_wallpaper` |
| 18 | `ensure_essential_apps_installed` |
| 19 | `clear_recent_tasks` |
