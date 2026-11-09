# Language

대여 기기에서 일본어, 영어, 한국어, 중국어 간체, 중국어 번체를 선택하는 독립 앱입니다. 선택한 언어를 기기의 기본 언어로 올리고 기존 보조 언어를 유지합니다.

Android 초기화 앱에는 Language APK가 포함됩니다. 필수 앱 단계에서 설치와 권한을 확인하고, 마지막에 삼성 메인 홈 화면의 오른쪽 아래에서 두 번째 줄에 아이콘을 배치합니다.

`android` 디렉터리에서 테스트 APK를 빌드한 다음, 운영자가 기기 준비 시 설치와 권한 부여를 한 번 실행합니다.

```sh
./gradlew :language-picker:assembleDebug
adb install -r language-picker/build/outputs/apk/debug/language-picker-debug.apk
adb shell pm grant --user 0 com.forholiday.language android.permission.CHANGE_CONFIGURATION
adb shell cmd appops set --user 0 com.forholiday.language WRITE_SETTINGS allow
```

고객은 홈 화면에서 **Language**를 열고 원하는 언어를 선택한 뒤 **변경하기**를 누르면 됩니다. Android 16이 설치된 S23 Ultra에서 시스템 언어 변경과 복구를 확인했습니다. 시스템 언어 쓰기는 Android 비공개 API를 사용하므로 새 One UI 버전에서 동작을 확인해야 합니다.
