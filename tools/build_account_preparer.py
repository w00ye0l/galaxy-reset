"""Rebuild the shared shell authenticator preparation DEX with SDK 36 / Java 17."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
sdk=Path(os.environ['ANDROID_HOME'])
java=Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory() as tmp:
    target=Path(tmp)
    sources=[ROOT/'AccountPreparer.java',ROOT/'android/app/src/main/java/com/forholiday/galaxyreset/AccountPreparationPolicy.java']
    subprocess.run([str(java/'javac'),'-source','8','-target','8','-classpath',str(sdk/'platforms/android-36/android.jar'),'-d',tmp,*map(str,sources)],check=True)
    subprocess.run([str(sdk/'build-tools/36.1.0/d8'),'--min-api','30','--lib',str(sdk/'platforms/android-36/android.jar'),'--output',tmp,*map(str,target.rglob('*.class'))],check=True)
    (ROOT/'account_preparer.dex').write_bytes((target/'classes.dex').read_bytes())
