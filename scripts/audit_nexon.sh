#!/usr/bin/env bash
# FASE 3.1 — Audit: find stray nexon tokens after running rename_nexon.sh.
#
# Usage: bash fase3/scripts/audit_nexon.sh
#
# Exits 0 if clean, 1 if stray tokens found.
# Scans ALL text files (not just whitelisted extensions) to catch docs/comments.

set -uo pipefail

if [[ "$(git rev-parse --is-inside-work-tree 2>/dev/null)" != "true" ]]; then
  echo "ERROR: Not inside a git repository." >&2
  exit 1
fi

echo "Auditing for stray 'nexon' / 'Nexon' / 'NEXON' tokens..."
echo ""

# Skip directories that legitimately shouldn't be touched.
EXCLUDE_DIRS=( node_modules .git .gradle build dist .next .cache .idea .vscode .expo )
PRUNE_ARGS=()
for d in "${EXCLUDE_DIRS[@]}"; do
  PRUNE_ARGS+=( -path "./${d}" -prune -o )
done

# Find all text files.
mapfile -t FILES < <(find . "${PRUNE_ARGS[@]}" -type f -print 2>/dev/null)

STRAY=0
URL_OK=0
for f in "${FILES[@]}"; do
  # Skip binary files (heuristic: file command).
  if file --mime-encoding "$f" 2>/dev/null | grep -qv 'charset=binary'; then
    : # text file, continue
  else
    continue
  fi

  # Grep for any case variant of nexon as a word boundary.
  while IFS= read -r line; do
    # Allow nexon.com URLs (trademark).
    if [[ "$line" =~ https?://[a-zA-Z0-9.]*nexon\.com ]]; then
      URL_OK=$((URL_OK+1))
      continue
    fi
    # Allow license/attribution lines (Nexon Inc. credit).
    if [[ "$line" =~ (Nexon Inc\.|Nexon America|copyright.*Nexon) ]]; then
      URL_OK=$((URL_OK+1))
      continue
    fi
    echo "  STRAY  $f"
    echo "         $line"
    STRAY=$((STRAY+1))
  done < <(grep -niE '\b(nexon|Nexon|NEXON)\b' "$f" 2>/dev/null)
done

echo ""
echo "Stray tokens:      $STRAY"
echo "URL/credit tokens: $URL_OK (allowed)"

if [[ $STRAY -gt 0 ]]; then
  echo ""
  echo "FAIL — Fix stray tokens above before proceeding to FASE 3.2."
  exit 1
fi

echo "PASS — codebase is clean."
exit 0
