"""20대 병렬 초기화 시뮬레이션 테스트 (실기기 불필요, exe에 미포함).

V6._exec만 가짜 함대로 패치하고 run_command의 재시도·이탈 감지, run_fleet의
병렬 집계, 실패 기기 reboot 제외까지 실제 코드 경로를 그대로 검증한다.

주입 시나리오:
  - 20대 중 2대(SIM007, SIM013)는 파이프라인 중간에 '기기 이탈'
  - 나머지는 2% 확률의 산발적 타임아웃 (best-effort로 흡수돼야 함)

사용법: python3 test_simulation.py          # 검증 모드 (진행률 화면 없음)
        python3 test_simulation.py --ui     # 진행률 화면 켜고 육안 확인
"""
import random
import subprocess
import sys
import threading
import time
from collections import defaultdict

import V6

random.seed(20)  # 재현 가능한 실행

SERIALS = ['SIM%03d' % i for i in range(20)]
MODEL_BY_SERIAL = {s: 'SM-S9%d8N' % (1 + i % 4) for i, s in enumerate(SERIALS)}

# 이탈 주입: 시리얼 -> 해당 기기의 N번째 adb 명령부터 '연결 끊김'
DROPPED = {'SIM007': 40, 'SIM013': 90}


class FakeFleet:
    def __init__(self):
        self.counts = defaultdict(int)
        self.lock = threading.Lock()
        self.step_at_drop = {}  # 이탈 시점에 돌고 있던 단계 라벨 기록용

    def exec(self, cmd, timeout):
        time.sleep(random.uniform(0.002, 0.01))
        serial = V6._adb_serial(cmd)

        if serial is None:  # 'adb devices' 등
            if 'devices' in cmd:
                rows = ['List of devices attached'] + ['%s\tdevice' % s for s in SERIALS]
                return _cp(cmd, 0, '\n'.join(rows) + '\n')
            return _cp(cmd, 0, '')

        with self.lock:
            self.counts[serial] += 1
            count = self.counts[serial]

        dropped = serial in DROPPED and count >= DROPPED[serial]
        if dropped:
            if 'get-state' in cmd:
                return _cp(cmd, 1, '', 'error: device offline')
            return _cp(cmd, 1, '', "adb: device '%s' not found" % serial)

        if 'get-state' in cmd:
            return _cp(cmd, 0, 'device\n')
        if random.random() < 0.02:  # 산발 타임아웃 (기기는 살아있음)
            raise subprocess.TimeoutExpired(cmd, timeout)
        if 'getprop' in cmd:
            return _cp(cmd, 0, MODEL_BY_SERIAL[serial] + '\n')
        if 'pm' in cmd and 'list' in cmd:
            return _cp(cmd, 0, 'package:com.example.userapp\n')
        if cmd[3] == 'install':
            return _cp(cmd, 0, 'Success\n')
        if 'dumpsys' in cmd:
            return _cp(cmd, 0, 'Widgets:\nHosts:\n')
        return _cp(cmd, 0, 'SUCCESS\n')


def _cp(cmd, rc, out, err=''):
    return subprocess.CompletedProcess(cmd, returncode=rc, stdout=out, stderr=err)


def main():
    use_ui = '--ui' in sys.argv
    fleet = FakeFleet()

    # ---- 패치: _exec(유일한 subprocess 통로)과 sleep(런처 대기 축소)만 ----
    V6._exec = fleet.exec
    real_sleep = time.sleep
    V6.time.sleep = lambda s: real_sleep(min(s, 0.02))

    log_buffer = V6.DeviceLogHandler()
    V6.logging.getLogger().addHandler(log_buffer)

    if not use_ui:
        # 진행률 화면 비활성 (isatty 검사로 자동 우회되지만 명시적으로)
        V6.ProgressConsole.start = lambda self, serials: False

    watchdog = threading.Timer(120, lambda: (_fail('워치독 120초 초과 — 행 발생'), ))
    watchdog.daemon = True
    watchdog.start()

    started = time.time()
    failures = V6.run_fleet(SERIALS, 'ja-JP')
    elapsed = time.time() - started
    watchdog.cancel()

    # ---- 단언 ----
    checks = []

    def check(name, ok, detail=''):
        checks.append((name, ok, detail))

    settled = len(SERIALS)  # run_fleet가 반환했다는 것 자체가 전원 정착의 1차 증거
    check('① 20대 전원 정착 (행 없음, %.1fs)' % elapsed, True)

    check('② 이탈 2대가 실패로 기록', set(DROPPED) <= set(failures),
          '실패 목록: %s' % list(failures))
    for serial in DROPPED:
        reason = failures.get(serial, '')
        check('   %s 실패 사유에 단계명 포함' % serial,
              '중 실패' in reason and '기기 연결 끊김' in reason, reason)

    healthy = [s for s in SERIALS if s not in DROPPED]
    wrongly_failed = [s for s in healthy if s in failures]
    check('③ 산발 타임아웃만 겪은 18대는 실패 아님 (best-effort 보존)',
          not wrongly_failed, '오판 실패: %s' % wrongly_failed)

    survivors = [s for s in SERIALS if s not in failures]
    check('④ reboot 대상에서 이탈 기기 제외',
          all(s not in survivors for s in DROPPED) and len(survivors) == 18)

    tail = log_buffer.tail('SIM007')
    only_sim007 = all('SIM007' in line or '[' not in line for line in tail)
    check('⑤ 실패 기기 로그 tail 존재 + 해당 기기 것만', bool(tail) and only_sim007,
          '%d줄' % len(tail))

    # scan_devices가 문제 상태를 버리지 않는지
    def fake_scan_exec(cmd, timeout):
        return _cp(cmd, 0, 'List of devices attached\n'
                           'GOOD1\tdevice\nBAD1\tunauthorized\nBAD2\toffline\n')
    V6._exec = fake_scan_exec
    ready, problems = V6.scan_devices()
    check('⑥ scan_devices가 unauthorized/offline을 problems로 보고',
          ready == ['GOOD1'] and sorted(p[1] for p in problems) == ['offline', 'unauthorized'],
          'ready=%s problems=%s' % (ready, problems))

    print()
    print('=' * 64)
    failed = 0
    for name, ok, detail in checks:
        mark = 'PASS' if ok else 'FAIL'
        failed += 0 if ok else 1
        print('[%s] %s%s' % (mark, name, ('  — ' + detail) if detail and not ok else ''))
    print('=' * 64)
    print('결과: %d/%d 통과' % (len(checks) - failed, len(checks)))
    sys.exit(1 if failed else 0)


def _fail(msg):
    print('FATAL:', msg)
    import os
    os._exit(2)


if __name__ == '__main__':
    main()
