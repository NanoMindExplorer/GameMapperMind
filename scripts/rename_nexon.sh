#!/usr/bin/env bash
# FASE 3.1 — Rename nexon → GameMapper (and Nexon → GameMapper) across the codebase.
#
# Usage (run from repo root):
#   bash fase3/scripts/rename_nexon.sh
#
# Safety:
#   - Refuses to run if git working tree is dirty.
#   - Creates a backup branch `pre-nexon-rename-<timestamp>` before touching files.
#   - Operates only on whitelisted file extensions (no binary, no node_modules, no .git).
#   - Prints a diff summary at the end; user must `git diff --stat` to verify before committing.
#
# What gets renamed:
#   nexon       → gamemapper   (lowercase identifiers, package paths)
#   Nexon       → GameMapper   (PascalCase: class names, type names)
#   NEXON       → GAMEMAPPER   (UPPERCASE: constants, log tags)
#   "nexon"     → "gamemapper" (string literals — be careful, may match brand references)
#
# What does NOT get renamed (skipped by design):
#   - `Nexon Inc.` / `Nexon America` brand references in licenses/credits (whitelisted paths)
#   - URLs containing "nexon.com" (preserved for trademark compliance)

set -euo pipefail

if [[ "$(git rev-parse --is-inside-work-tree 2>/dev/null)" != "true" ]]; then
  echo "ERROR: Not inside a git repository. cd to repo root first." >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "ERROR: Working tree is dirty. Commit or stash changes first." >&2
  git status --short
  exit 1
fi

# Backup branch.
TS="$(date +%Y%m%d-%H%M%S)"
BACKUP_BRANCH="pre-nexon-rename-${TS}"
git branch "$BACKUP_BRANCH"
echo "Backup branch created: $BACKUP_BRANCH"

# Whitelist of file extensions to touch.
EXTENSIONS=(
  kt java ts tsx js jsx mjs cjs
  json json5 jsonc
  xml gradle groovy
  md mdx
  yaml yml
  aidl
  sh bash
  toml properties
)

# Build find expression.
FIND_ARGS=()
for ext in "${EXTENSIONS[@]}"; do
  FIND_ARGS+=( -o -iname "*.${ext}" )
done
FIND_ARGS=( "${FIND_ARGS[@]:2}" )  # strip leading -o

# Directories to exclude.
EXCLUDE_DIRS=( node_modules .git .gradle build dist .next .cache .idea .vscode )

PRUNE_ARGS=()
for d in "${EXCLUDE_DIRS[@]}"; do
  PRUNE_ARGS+=( -path "./${d}" -prune -o )
done

echo "Scanning files..."
FILES=( $(find . "${PRUNE_ARGS[@]}" -type f \( "${FIND_ARGS[@]}" \) -print) )

echo "Found ${#FILES[@]} candidate files."

# Paths that should NEVER be touched (brand/license references).
PROTECTED_REGEX='(LICENSE|NOTICE|CREDITS|third_party|/docs/trademark)'

# Substitution patterns.
# Pattern 1: nexon → gamemapper (lowercase)
# Pattern 2: Nexon → GameMapper (PascalCase)
# Pattern 3: NEXON → GAMEMAPPER (uppercase)
# Pattern 4: "nexon" → "gamemapper" (string literal — keep this explicit to avoid
#            false matches in URLs like nexon.com which should NOT change)

CHANGED=0
for f in "${FILES[@]}"; do
  if [[ "$f" =~ $PROTECTED_REGEX ]]; then
    continue
  fi

  # Skip if file contains nexon.com URL — preserve trademark references.
  if grep -qE 'https?://[a-z0-9.]*nexon\.com' "$f" 2>/dev/null; then
    : # File contains nexon.com URL; we'll still rename non-URL tokens below.
  fi

  # Use perl for word-boundary safety (\b) and unicode awareness.
  if perl -i -pe '
    # Skip URL-embedded nexon tokens (preserve nexon.com).
    s{\b(nexon)\.com}{KEEP_NEXON_DOT_COM}g;
    s{\b(Nexon)\.com}{KEEP_NEXON_DOT_COM_PASCAL}g;

    # Core renames.
    s{\b(nexon)\b}{gamemapper}gi
      while s{\bnexon\b}{gamemapper}g
          || s{\bNexon\b}{GameMapper}g
          || s{\bNEXON\b}{GAMEMAPPER}g;

    # Restore preserved tokens.
    s{KEEP_NEXON_DOT_COM_PASCAL}{Nexon.com}g;
    s{KEEP_NEXON_DOT_COM}{nexon.com}g;
  ' "$f" 2>/dev/null; then
    if ! git diff --quiet -- "$f" 2>/dev/null; then
      CHANGED=$((CHANGED+1))
    fi
  fi
done

echo ""
echo "Modified $CHANGED files."
echo ""
echo "Next steps:"
echo "  1. git diff --stat               (review scope of changes)"
echo "  2. git diff -- android/app/src   (review Android manifest + Kotlin)"
echo "  3. git diff -- src/              (review TS sources)"
echo "  4. Run a clean build to catch any rename miss:"
echo "       cd android && ./gradlew clean assembleDebug"
echo "  5. If build fails, search for stray nexon tokens:"
echo "       grep -rniE '\\b(nexon|Nexon|NEXON)\\b' --include='*.kt' --include='*.java' --include='*.ts' --include='*.tsx' --include='*.xml' ."
echo "  6. Once verified, commit:"
echo "       git add -A && git commit -m 'refactor: rename nexon → GameMapper (FASE 3.1)'"
echo ""
echo "Rollback (if needed):"
echo "  git reset --hard $BACKUP_BRANCH"
