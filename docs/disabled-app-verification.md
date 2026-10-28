# 1.1.18 비활성 앱 검증

검증일: 2026-10-28. 기기: Galaxy S25 / SM-S938N. 전체 초기화 대신 계정 단계·지정 앱 데이터 삭제·진행 화면만 검사했다. 삼성 계정 식별자는 메모리에서만 비교하며 로그에 남기지 않았다.

| 항목 | 검사 방식 | 결과 |
|---|---|---|
| 비활성 Meet 실제 잔여 계정 | 앱 활성화 → 기존 계정 제거 DEX → Meet 데이터 삭제 → 현재 계정 확인 | 삼성 계정만 남음 |
| 앱 전체 비활성 계정 제공자 | 자체 테스트 앱에 가상 계정 추가 → disable-user → 실제 V6 계정 단계 | 인증 앱 자동 활성화·가상 계정 제거, 삼성 계정 유지 |
| 개별 인증 서비스 비활성 | 테스트 앱이 자신의 서비스만 비활성화 → 실제 V6 계정 단계 | 인증 서비스 검증 실패. 완료로 처리하지 않음 |
| 위 서비스 복구 후 재시도 | 테스트 앱에서 서비스 복구 → 실제 V6 계정 단계 | 가상 계정 제거, 삼성 계정 유지 |
| 네이버 지도·파일관리자·Meet 데이터 | 각각 disable-user → 실제 V6.clear_app_data → 비활성 상태 확인 | 세 앱 모두 Success, 검사 후 모두 활성화 |
| 진행 화면 | 실제 기기에서 비파괴 instrumentation으로 마지막 기록까지 자동 스크롤 | 진행률 Y 좌표 유지, 내부 기록 스크롤, 상세 창 열기 통과 |
| 기타 계정 제공자 탐색 | 설치 APK의 인증 서비스 메타데이터 읽기만 실행 | Google, 비활성 Gmail, Microsoft 계열 등 탐색 확인. 실제 로그인 계정 제거를 각각 검증한 것은 아님 |

테스트 계정 및 테스트 앱은 검사 후 제거했고, 현재 계정 목록이 검사 전의 삼성 계정만 남은 상태와 일치하는지 확인했다. 서비스 계정 탈퇴·계정 DB 직접 수정·잠금 우회는 하지 않았다.

## 자동 검사

- PC 회귀 검사 48개 통과. Android 단위 검사 49개 통과, lint·빌드 통과. 병렬 시뮬레이션 8/8 통과.
- PC: `python -m unittest -v test_phone_setup test_phone_autostart test_naver_account_cleanup`
- 병렬 초기화 실패 전파: `python test_simulation.py`
- Android: `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
- 설치한 테스트 APK의 진행 화면 검사: `adb shell am instrument -w -r com.forholiday.galaxyreset.test/com.forholiday.galaxyreset.ProgressLayoutCheck`
- 계정 준비 헬퍼 재빌드: Java 17과 SDK 36/Build Tools 36.1.0 환경에서 `python tools/build_account_preparer.py`. 그 뒤 Android APK를 다시 빌드하고 `tools/prepare_phone_bundle.py`로 EXE 번들을 갱신한다.

`android/disabled-auth-fixture`는 `-PvalidationFixture`일 때만 포함되는 검증 전용 모듈이다. 배포 앱에 포함되지 않는다. 실제 고객 데이터가 없는 검증 기기에서만 설치한다. 테스트 계정은 이 앱의 `AddAccount` 화면으로 추가하고 `--ez cleanup true`로 제거한다. 개별 서비스 비활성 검사는 `--ez disable-service true`를 사용하며 일반 실행으로 복구한다. 종료 전에 cleanup으로 현재 가상 계정을 제거한 뒤 테스트 APK도 삭제한다.

## 적용 범위

앱 전체 비활성화는 실제 남은 계정과 인증 APK를 대조해 자동 복구한다. 개별 서비스 비활성화, 기기 정책·제공자의 제거 거부, 알 수 없는 확인창 등은 성공을 보장할 수 없다. 이러한 경우 실패를 표시하고 현재 비삼성 계정의 잔존 여부를 확인한다. 모든 앱의 모든 로그인 세션을 이번에 각각 로그인해서 검사한 것은 아니다. 삼성 계정은 보존한다.
