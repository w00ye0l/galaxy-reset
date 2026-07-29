"""실제 adb 환경 스모크 테스트 (exe에 미포함).

기기가 연결되어 있으면 비파괴 검사까지, 없으면 adb stderr 패턴 검사만 수행한다.
전체 초기화는 실행하지 않는다 — 기기 데이터에 영향 없음.
"""
import glob
import os
import sys

import V6


def main():
    checks = []

    def check(name, ok, detail=''):
        checks.append((name, ok, detail))

    # 1. 존재하지 않는 시리얼 → 실제 adb의 stderr 패턴으로 DeviceLostError 발생
    try:
        V6.run_command(['adb', '-s', 'NOPE123', 'shell', 'echo', 'hi'], retries=0)
        check('가짜 시리얼에서 DeviceLostError 발생', False, '예외 없이 통과함')
    except V6.DeviceLostError as e:
        check('가짜 시리얼에서 DeviceLostError 발생', True, str(e))

    # 2. device_alive가 없는 기기에서 raise 없이 False
    check('device_alive(가짜) == False (raise 없음)', V6.device_alive('NOPE123') is False)

    # 3. scan_devices 파싱 (헤더/배너 제외, 상태 분류)
    ready, problems = V6.scan_devices()
    check('scan_devices 정상 반환', isinstance(ready, list) and isinstance(problems, list),
          'ready=%s problems=%s' % (ready, problems))

    # 4. 인메모리 로그 버퍼: 태깅 + tail + 디스크 무기록
    buffer = V6.DeviceLogHandler()
    V6.logging.getLogger().addHandler(buffer)
    V6._TLS.serial = 'SMOKE1'
    V6.logging.info('스모크 테스트 라인 1')
    V6.logging.warning('[SMOKE1] 스모크 테스트 라인 2')
    V6._TLS.serial = None
    V6.logging.getLogger().removeHandler(buffer)
    tail = buffer.tail('SMOKE1')
    check('로그 버퍼 tail 2줄', len(tail) == 2, '%d줄' % len(tail))
    logs_on_disk = glob.glob(os.path.join(os.path.dirname(os.path.abspath(V6.__file__)), '*로그*'))
    check('디스크에 로그 파일 없음', not logs_on_disk, str(logs_on_disk))

    if ready:
        serial = ready[0]
        # 5. 실기기 비파괴 왕복
        check('device_alive(실기기) == True', V6.device_alive(serial))
        model = V6.get_device_model(serial)
        check('모델 조회', bool(model), model)
        check('시리즈 판정', V6.series_from_model(model, serial) in ('S23', 'S24', 'S25', 'S26'))
        result = V6.run_command(['adb', '-s', serial, 'shell', 'dumpsys', 'appwidget'])
        check('dumpsys appwidget 왕복', result.returncode == 0 and bool(result.stdout))
        result = V6.run_command(['adb', '-s', serial, 'shell', 'pm', 'list', 'packages'], timeout=120)
        check('pm list packages 왕복', result.returncode == 0 and 'package:' in result.stdout)
    else:
        print('(기기 미연결 — 실기기 검사는 건너뜀)')

    print()
    print('=' * 64)
    failed = 0
    for name, ok, detail in checks:
        mark = 'PASS' if ok else 'FAIL'
        failed += 0 if ok else 1
        print('[%s] %s%s' % (mark, name, ('  — ' + detail) if detail else ''))
    print('=' * 64)
    print('결과: %d/%d 통과' % (len(checks) - failed, len(checks)))
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
