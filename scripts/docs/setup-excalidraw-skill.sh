#!/usr/bin/env bash
set -euo pipefail

repo_url=https://github.com/coleam00/excalidraw-diagram-skill.git
revision=8646fcc9f74f38539c6cdb4c969723336a96ddcd
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
skills_dir="$repo_root/.agents/skills"
destination="$skills_dir/excalidraw-diagram"

command -v git >/dev/null || { echo "Git is required." >&2; exit 1; }
command -v node >/dev/null && command -v npm >/dev/null || { echo "Node.js and npm are required for the local Excalidraw bundle." >&2; exit 1; }
command -v uv >/dev/null || { echo "uv is required for the Excalidraw renderer." >&2; exit 1; }

if [[ -e "$destination" || -L "$destination" ]]; then
    if [[ ! -f "$destination/SKILL.md" || ! -f "$destination/.pam-upstream-revision" ]] ||
       [[ "$(cat "$destination/.pam-upstream-revision")" != "$revision" ]]; then
        echo "Existing skill at $destination does not match the pinned install. Review it before replacing it." >&2
        exit 1
    fi
else
    mkdir -p "$skills_dir"
    staging="$(mktemp -d "$skills_dir/.excalidraw-install.XXXXXX")"
    trap 'rm -rf -- "$staging"' EXIT

    git -C "$staging" init --quiet
    git -C "$staging" fetch --quiet --depth=1 "$repo_url" "$revision"
    [[ "$(git -C "$staging" rev-parse FETCH_HEAD)" == "$revision" ]]

    mkdir "$staging/skill"
    git -C "$staging" archive "$revision" | tar -xf - -C "$staging/skill"
    test -f "$staging/skill/SKILL.md"
    printf '%s\n' "$revision" > "$staging/skill/.pam-upstream-revision"
    mv "$staging/skill" "$destination"
fi

cd "$destination/references"
uv sync
uv run playwright install chromium

# The upstream CDN import currently fails on a transitive dependency.
# Keep the upstream Python renderer and load a local copy of exportToSvg.
mkdir -p .pam-render
npm install --prefix .pam-render --no-audit --no-fund --ignore-scripts --save-exact \
    @excalidraw/excalidraw@0.18.1 esbuild@0.25.12 react@18.3.1 react-dom@18.3.1
printf '%s\n' 'export { exportToSvg } from "@excalidraw/excalidraw";' > .pam-render/entry.js
.pam-render/node_modules/.bin/esbuild .pam-render/entry.js \
    --bundle --minify --format=iife --global-name=PamExcalidraw \
    --define:process.env.NODE_ENV='"production"' --outfile=.pam-render/bundle.js

node <<'JS'
const fs = require('node:fs');
const path = 'render_template.html';
const original = '  <script type="module">\n    import { exportToSvg } from "https://esm.sh/@excalidraw/excalidraw?bundle";';
const local = '  <script src=".pam-render/bundle.js"></script>\n  <script>\n    const { exportToSvg } = window.PamExcalidraw;';
const template = fs.readFileSync(path, 'utf8');
if (template.includes(original)) {
    fs.writeFileSync(path, template.replace(original, local));
} else if (!template.includes(local)) {
    throw new Error('Unexpected upstream renderer template');
}
JS

printf 'Excalidraw skill installed at %s\n' "$destination/SKILL.md"
