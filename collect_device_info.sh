#!/bin/bash
# 기기 정보 수집 스크립트
# 사용법: ./collect_device_info.sh [시리얼번호]
# 시리얼번호 생략 시 연결된 첫 번째 기기 사용

export PATH="/Users/forholiday/Library/Android/sdk/platform-tools:$PATH"

if [ -n "$1" ]; then
    S="$1"
else
    S=$(adb devices | grep -w "device" | head -1 | awk '{print $1}')
fi

if [ -z "$S" ]; then
    echo "연결된 기기가 없습니다."
    exit 1
fi

echo "=== 기기: $S ==="

# 기기 태그 (설정된 기기 이름)
DEVICE_NAME=$(adb -s "$S" shell settings get global device_name 2>/dev/null | tr -d '\r')

# 모델
MODEL=$(adb -s "$S" shell getprop ro.product.model 2>/dev/null | tr -d '\r')

# IMEI1 - service call iphonesubinfo 1
IMEI1_RAW=$(adb -s "$S" shell "service call iphonesubinfo 1 s16 com.android.shell" 2>&1)
IMEI1=$(echo "$IMEI1_RAW" | grep -oE "'[^']+'" | sed "s/'//g" | tr -d '.' | tr -d ' ' | sed 's/[^0-9]//g')

# IMEI2 - service call iphonesubinfo 4
IMEI2_RAW=$(adb -s "$S" shell "service call iphonesubinfo 4 i32 1 s16 com.android.shell" 2>&1)
IMEI2=$(echo "$IMEI2_RAW" | grep -oE "'[^']+'" | sed "s/'//g" | tr -d '.' | tr -d ' ' | sed 's/[^0-9]//g')

# WiFi MAC
WIFI_MAC=$(adb -s "$S" shell "dumpsys wifi" 2>&1 | grep "wifi_sta_factory_mac_address" | head -1 | awk -F= '{print $2}' | tr -d '\r')

# EID - radio 로그에서 890으로 시작하는 32자리
EID=$(adb -s "$S" shell "logcat -d -b radio" 2>&1 | grep -oE "890[0-9]{29}" | head -1)

echo "기기태그: $DEVICE_NAME"
echo "모델:    $MODEL"
echo "IMEI1:   $IMEI1"
echo "IMEI2:   $IMEI2"
echo "S/N:     $S"
echo "WiFi MAC: $WIFI_MAC"
echo "EID:     ${EID:-없음 (radio 로그에 없음 - 설정>휴대전화정보>상태정보 또는 *#06#에서 직접 확인)}"
echo ""
echo "=== device_info.txt 형식 ==="
echo "${DEVICE_NAME}	${IMEI1}	${IMEI2}	${S}	${WIFI_MAC}	${EID}"
