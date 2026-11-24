#!/usr/bin/env python3
"""Local REAL-UAT launcher: loads private profiles and starts the Go API.
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
if env.get('LLM_PROVIDER') == 'azure':
    # UAT uses the existing Foundry deployment for both features. The API still
    # enforces separate consent, payload and accounting for each feature.
    for target, source in {
        'LLM_BASE_URL': 'AZURE_CLASSIFIER_BASE_URL',
        'LLM_API_KEY': 'AZURE_CLASSIFIER_API_KEY',
        'LLM_MODEL': 'AZURE_CLASSIFIER_DEPLOYMENT',
        'PRICE_PROMPT_PER_1K_USD': 'CATEGORY_LLM_PRICE_PROMPT_PER_1K_USD',
        'PRICE_COMPLETION_PER_1K_USD': 'CATEGORY_LLM_PRICE_COMPLETION_PER_1K_USD',
    }.items():
        env[target] = env.get(source, '')
os.execvpe(sys.argv[1], sys.argv[1:], env)
