#!/bin/bash
# verify_contract.sh - Verification Gate untuk Kontrak AI v3.0
# WAJIB dijalankan setelah setiap task. Exit code 0 = semua gate PASS.
# PELANGGARAN: jika script ini di-stub, di-delete, atau di-bypass = BREACH Level 4.
#
# History:
# - v1.0: 9 gate (kontrak v1.0)
# - v2.0: 15 gate (kontrak v2.0) — Gemini membuat stub 11 baris (BREACH)
# - v2.0 fix attempt: AI Worker ke-2 meng-DELETE file ini (BREACH Level 4)
# - v3.0: 18 gate (kontrak v3.0) — tambah 3 gate pseudo-fix detection

set -e

echo "================================================"
echo "  KONTRAK v3.0 - VERIFICATION GATE (18 gates)"
echo "================================================"
echo ""

FAILURES=0

# Gate 1: TypeScript Lint
echo "[Gate 1/18] TypeScript Lint..."
if npm run lint 2>&1 | tee /tmp/lint.log | grep -q "error TS"; then
  echo "  ❌ FAIL: TypeScript errors detected"
  FAILURES=$((FAILURES + 1))
else
  echo "  ✅ PASS"
fi

# Gate 2: Kotlin Compile
echo "[Gate 2/18] Kotlin Compile..."
if cd android && ./gradlew compileDebugKotlin 2>&1 | tee /tmp/kt.log | grep -q "BUILD SUCCESSFUL"; then
  echo "  ✅ PASS"
  cd ..
else
  echo "  ❌ FAIL: Kotlin compilation failed"
  cd ..
  FAILURES=$((FAILURES + 1))
fi

# Gate 3: Vite Build
echo "[Gate 3/18] Vite Build..."
if npm run build 2>&1 | tee /tmp/build.log | grep -q "built"; then
  echo "  ✅ PASS"
else
  echo "  ❌ FAIL: Vite build failed"
  FAILURES=$((FAILURES + 1))
fi

# Gate 4: Unit Test TS
echo "[Gate 4/18] Unit Test (vitest)..."
if npm test 2>&1 | tee /tmp/test.log | grep -q "passed"; then
  echo "  ✅ PASS"
else
  echo "  ❌ FAIL: Tests failed"
  FAILURES=$((FAILURES + 1))
fi

# Gate 5: Coverage
echo "[Gate 5/18] Coverage check (>= 80%)..."
COVERAGE=$(npm run coverage 2>&1 | grep "All files" | awk '{print $NF}' | tr -d '%')
if [ -n "$COVERAGE" ] && [ "$COVERAGE" -ge 80 ] 2>/dev/null; then
  echo "  ✅ PASS (coverage: ${COVERAGE}%)"
else
  echo "  ❌ FAIL: coverage ${COVERAGE}% < 80%"
  FAILURES=$((FAILURES + 1))
fi

# Gate 6: Mock Detector
echo "[Gate 6/18] Mock Detector..."
MOCK_HITS=$(grep -rE "\b(mock|stub|fake)\b" src/ --include="*.ts" --include="*.tsx" | grep -v ".test." | grep -v ".spec." | wc -l)
if [ "$MOCK_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no mocks)"
else
  echo "  ❌ FAIL: $MOCK_HITS mock matches found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 7: Elipsis Detector
echo "[Gate 7/18] Elipsis Detector..."
ELLIPSIS_HITS=$(grep -r "\.\.\." src/ --include="*.ts" --include="*.tsx" | grep -v "spread" | grep -v "\.\.\.[a-zA-Z]" | wc -l)
if [ "$ELLIPSIS_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no elipsis)"
else
  echo "  ❌ FAIL: $ELLIPSIS_HITS elipsis matches found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 8: TODO Detector
echo "[Gate 8/18] TODO/FIXME Detector..."
TODO_HITS=$(grep -rE "\b(TODO|FIXME|HACK|XXX|STUB)\b" src/ --include="*.ts" --include="*.tsx" | grep -v "issue-tracker:" | wc -l)
if [ "$TODO_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no TODOs)"
else
  echo "  ❌ FAIL: $TODO_HITS TODO matches found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 9: Hardcode Detector
echo "[Gate 9/18] Hardcoded Secret Detector..."
HARDCODE_HITS=$(grep -rE "(TOKEN|SECRET|PASSWORD)\s*=" src/ server.ts | grep -v "process.env" | wc -l)
if [ "$HARDCODE_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no hardcoded secrets)"
else
  echo "  ❌ FAIL: $HARDCODE_HITS hardcoded secrets found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 10: Secret Commit Detector (v2.0)
echo "[Gate 10/18] Secret File Commit Detector..."
SECRET_FILES=$(git ls-files | grep -E 'admin_token|app_data|\.env|\.key|\.pem|\.data_key' | wc -l)
if [ "$SECRET_FILES" -eq 0 ]; then
  echo "  ✅ PASS (no secret files in git)"
else
  echo "  ❌ FAIL: $SECRET_FILES secret files found in git"
  FAILURES=$((FAILURES + 1))
fi

# Gate 11: Vitest Installed (v2.0)
echo "[Gate 11/18] Vitest Installed..."
if grep -q "vitest" package.json; then
  echo "  ✅ PASS"
else
  echo "  ❌ FAIL: vitest not in package.json"
  FAILURES=$((FAILURES + 1))
fi

# Gate 12: Verify Script Lengkap (v2.0)
echo "[Gate 12/18] Verify Script Lengkap..."
if [ -f verify_contract.sh ]; then
  SCRIPT_LINES=$(wc -l < verify_contract.sh)
  if [ "$SCRIPT_LINES" -ge 80 ]; then
    echo "  ✅ PASS ($SCRIPT_LINES lines)"
  else
    echo "  ❌ FAIL: verify_contract.sh only $SCRIPT_LINES lines (need >= 80)"
    FAILURES=$((FAILURES + 1))
  fi
else
  echo "  ❌ FAIL: verify_contract.sh MISSING (BREACH Level 4 — file deleted?)"
  FAILURES=$((FAILURES + 1))
fi

# Gate 13: No Trivial Test (v2.0)
echo "[Gate 13/18] No Trivial Test..."
TRIVIAL_HITS=$(grep -rE 'expect\(1\)\.toBe\(1\)|expect\(true\)\.toBe\(true\)|expect\([a-zA-Z]+\s*\*\s*\d+\)\.toEqual' tests/ | wc -l)
if [ "$TRIVIAL_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no trivial tests)"
else
  echo "  ❌ FAIL: $TRIVIAL_HITS trivial tests found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 14: Native Kotlin Modified (v2.0)
echo "[Gate 14/18] Native Kotlin Modified (if applicable)..."
KT_MODIFIED=$(git log --name-only -1 | grep '\.kt$' | wc -l)
if [ "$KT_MODIFIED" -ge 1 ]; then
  echo "  ✅ PASS ($KT_MODIFIED Kotlin files modified in last commit)"
else
  echo "  ⚠️ WARN: No Kotlin files modified in last commit (OK if not working on native bugs)"
fi

# Gate 15: APK Build (v2.0)
echo "[Gate 15/18] APK Build..."
if cd android && ./gradlew assembleDebug 2>&1 | tee /tmp/apk.log | grep -q "BUILD SUCCESSFUL"; then
  echo "  ✅ PASS"
  cd ..
else
  echo "  ❌ FAIL: APK build failed"
  cd ..
  FAILURES=$((FAILURES + 1))
fi

# Gate 16: Pseudo-Fix — Unused Variable (BARU v3.0)
echo "[Gate 16/18] Pseudo-Fix: Unused Variable Detector..."
UNUSED_HITS=$(npx tsc --noUnusedLocals --noEmit 2>&1 | grep -c "is declared but")
# if UNUSED_HITS is empty or not a number due to non match, handle it nicely
if [ -z "$UNUSED_HITS" ] || [ "$UNUSED_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no unused variables)"
else
  echo "  ❌ FAIL: $UNUSED_HITS unused variables found (PSEUDO-FIX DETECTED)"
  FAILURES=$((FAILURES + 1))
fi

# Gate 17: Pseudo-Fix — Test Import Module (BARU v3.0)
echo "[Gate 17/18] Pseudo-Fix: Test Import Module Under Test..."
TEST_ISSUES=""
for f in tests/*.test.ts; do
  if [ -f "$f" ]; then
    MODULE=$(basename "$f" .test.ts)
    if ! grep -qE "import.*$MODULE|from.*$MODULE|from.*src/" "$f"; then
      TEST_ISSUES="$TEST_ISSUES $f"
    fi
  fi
done
if [ -z "$TEST_ISSUES" ]; then
  echo "  ✅ PASS (all tests import their modules)"
else
  echo "  ❌ FAIL: tests not importing modules:$TEST_ISSUES"
  FAILURES=$((FAILURES + 1))
fi

# Gate 18: Pseudo-Fix — File Existence (BARU v3.0)
echo "[Gate 18/18] Pseudo-Fix: Critical File Existence..."
MISSING_FILES=""
for f in verify_contract.sh vitest.config.ts package.json server.ts tsconfig.json; do
  if [ ! -f "$f" ]; then
    MISSING_FILES="$MISSING_FILES $f"
  fi
done
if [ -z "$MISSING_FILES" ]; then
  echo "  ✅ PASS (all critical files exist)"
else
  echo "  ❌ FAIL: missing files:$MISSING_FILES"
  FAILURES=$((FAILURES + 1))
fi

echo ""
echo "================================================"
echo "  VERIFICATION SUMMARY (v3.0 — 18 gates)"
echo "================================================"
if [ "$FAILURES" -eq 0 ]; then
  echo "  ✅ ALL GATES PASSED"
  echo "================================================"
  exit 0
else
  echo "  ❌ $FAILURES GATE(S) FAILED"
  echo "================================================"
  echo ""
  echo "If any gate failed with PSEUDO-FIX DETECTED, this is BREACH Level -1."
  echo "Senior Auditor (Super Z) will verify independently."
  exit 1
fi
