#!/usr/bin/env python3
"""Local REAL-UAT launcher: merges private env files, enables the classifier, execs the API.
Secrets stay in private files; this script prints no env values."""
from pathlib import Path
import os, sys
files = [Path.home()/'.config/pam/real-uat.env', Path.home()/'.config/pam/azure-classifier.env']
env = {}
for f in files:
    for line in f.read_text().splitlines():
        s = line.strip()
        if s and not s.startswith('#') and '=' in s:
            k, v = s.split('=', 1); env[k] = v
env['CATEGORY_LLM_ENABLED'] = 'true'
os.execvpe(sys.argv[1], sys.argv[1:], env)
