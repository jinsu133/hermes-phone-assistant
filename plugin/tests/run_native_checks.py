"""Run native CLI checks with a fresh home and without inherited credentials."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
hermes = Path(sys.executable).resolve().parents[2]
# venv python may be a symlink to Termux python, so use caller's explicit source path.
hermes = Path(os.environ['PYTHONPATH'].split(os.pathsep)[0])
for check in ('doctor','compat'):
    with tempfile.TemporaryDirectory(prefix='phone-native-check-') as home:
        env = {key:os.environ[key] for key in ('PATH','HOME','TMPDIR','PREFIX','LANG') if key in os.environ}
        env.update(HERMES_HOME=home, PYTHONPATH=str(hermes), PYTHONDONTWRITEBYTECODE='1', TZ='UTC')
        command = [sys.executable,'-m','hermes_cli.main','plugins',check,str(root)]
        if check == 'doctor':
            command += ['--ci']
        print('COMMAND:', ' '.join(command), flush=True)
        result = subprocess.run(command, env=env, cwd=home, text=True, timeout=90)
        print('EXIT:',result.returncode,flush=True)
        if result.returncode:
            raise SystemExit(result.returncode)
