#!/usr/bin/env python3
"""Local REAL-UAT launcher: loads private profiles and starts the Go API.
Secrets stay in private files; this script prints no env values.

The classifier is opt-in: pass PAM_UAT_CLASSIFIER=1 to enable it. The default is off so a
Gmail-only acceptance gate never makes Azure/categorization calls behind the user's back."""
from pathlib import Path
import os, sys
files = [Path.home()/'.config/pam/real-uat.env', Path.home()/'.config/pam/azure-classifier.env']
env = {}
for f in files:
    for line in f.read_text().splitlines():
        s = line.strip()
        if s and not s.startswith('#') and '=' in s:
            k, v = s.split('=', 1); env[k] = v
classifier_on = os.environ.get('PAM_UAT_CLASSIFIER', '0') == '1'
env['CATEGORY_LLM_ENABLED'] = 'true' if classifier_on else 'false'
if not classifier_on:
    # Keep the Azure values out of the process unless the classifier gate is explicitly on.
    for key in ('LLM_BASE_URL', 'LLM_API_KEY', 'LLM_MODEL',
                'PRICE_PROMPT_PER_1K_USD', 'PRICE_COMPLETION_PER_1K_USD'):
        env.pop(key, None)
if classifier_on and env.get('LLM_PROVIDER') == 'azure':
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
