# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec file for V6.py
# 사용법: pyinstaller V6.spec

a = Analysis(
    ['V6.py'],
    pathex=[],
    binaries=[],
    datas=[
        ('device_serial_map.json', '.'),
        ('S23.png', '.'),
        ('S24.png', '.'),
        ('S25.png', '.'),
        ('nmap.apk', '.'),
        ('dji_mimo.apk', '.'),
        ('filemanager.apk', '.'),
        ('Tasker.apk', '.'),
    ],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='갤럭시 초기화 V6',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
