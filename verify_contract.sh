#!/bin/bash
# verify_contract.sh - Verification Gate untuk Kontrak AI v2.0
# WAJIB dijalankan setelah setiap task. Exit code 0 = semua gate PASS.
# Pelanggaran: jika script ini di-stub atau di-bypass = BREACH Level 4.

set -e

echo "================================================"
echo "  KONTRAK v2.0 - VERIFICATION GATE (15 gates)"
echo "================================================"
echo ""

FAILURES=0

# Gate 1: TypeScript Lint
echo "[Gate 1/15] TypeScript Lint..."
if npm run lint 2>&1 | tee /tmp/lint.log | grep -q "error TS"; then
  echo "  ❌ FAIL: TypeScript errors detected"
  FAILURES=$((FAILURES + 1))
else
  echo "  ✅ PASS"
fi

# Gate 2: Kotlin Compile
echo "[Gate 2/15] Kotlin Compile..."
if cd android && ./gradlew compileDebugKotlin 2>&1 | tee /tmp/kt.log | grep -q "BUILD SUCCESSFUL"; then
  echo "  ✅ PASS"
  cd ..
else
  echo "  ❌ FAIL: Kotlin compilation failed"
  cd ..
  FAILURES=$((FAILURES + 1))
fi

# Gate 3: Vite Build
echo "[Gate 3/15] Vite Build..."
if npm run build 2>&1 | tee /tmp/build.log | grep -q "built"; then
  echo "  ✅ PASS"
else
  echo "  ❌ FAIL: Vite build failed"
  FAILURES=$((FAILURES + 1))
fi

# Gate 4: Unit Test TS
echo "[Gate 4/15] Unit Test (vitest)..."
if npm test 2>&1 | tee /tmp/test.log | grep -q "passed"; then
  echo "  ✅ PASS"
else
  echo "  ❌ FAIL: Tests failed"
  FAILURES=$((FAILURES + 1))
fi

# Gate 5: Coverage
echo "[Gate 5/15] Coverage check (>= 80%)..."
COVERAGE=$(npm run coverage 2>&1 | grep "All files" | awk '{print $NF}' | tr -d '%')
if [ -n "$COVERAGE" ] && [ "$COVERAGE" -ge 80 ] 2>/dev/null; then
  echo "  ✅ PASS (coverage: ${COVERAGE}%)"
else
  echo "  ❌ FAIL: coverage ${COVERAGE}% < 80%"
  FAILURES=$((FAILURES + 1))
fi

# Gate 6: Mock Detector
echo "[Gate 6/15] Mock Detector..."
MOCK_HITS=$(grep -rE "\b(mock|stub|fake)\b" src/ --include="*.ts" --include="*.tsx" | grep -v ".test." | grep -v ".spec." | wc -l)
if [ "$MOCK_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no mocks)"
else
  echo "  ❌ FAIL: $MOCK_HITS mock matches found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 7: Elipsis Detector
echo "[Gate 7/15] Elipsis Detector..."
ELLIPSIS_HITS=$(grep -r "\.\.\." src/ --include="*.ts" --include="*.tsx" | grep -v "spread" | grep -v "\.\.\.[a-zA-Z]" | wc -l)
if [ "$ELLIPSIS_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no elipsis)"
else
  echo "  ❌ FAIL: $ELLIPSIS_HITS elipsis matches found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 8: TODO Detector
echo "[Gate 8/15] TODO/FIXME Detector..."
TODO_HITS=$(grep -rE "\b(TODO|FIXME|HACK|XXX|STUB)\b" src/ --include="*.ts" --include="*.tsx" | grep -v "issue-tracker:" | wc -l)
if [ "$TODO_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no TODOs)"
else
  echo "  ❌ FAIL: $TODO_HITS TODO matches found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 9: Hardcode Detector
echo "[Gate 9/15] Hardcoded Secret Detector..."
HARDCODE_HITS=$(grep -rE "(TOKEN|SECRET|PASSWORD)\s*=" src/ server.ts | grep -v "process.env" | wc -l)
if [ "$HARDCODE_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no hardcoded secrets)"
else
  echo "  ❌ FAIL: $HARDCODE_HITS hardcoded secrets found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 10: Secret Commit Detector (BARU v2.0)
echo "[Gate 10/15] Secret File Commit Detector..."
SECRET_FILES=$(git ls-files | grep -E 'admin_token|app_data|\.env|\.key|\.pem' | wc -l)
if [ "$SECRET_FILES" -eq 0 ]; then
  echo "  ✅ PASS (no secret files in git)"
else
  echo "  ❌ FAIL: $SECRET_FILES secret files found in git"
  FAILURES=$((FAILURES + 1))
fi

# Gate 11: Vitest Installed (BARU v2.0)
echo "[Gate 11/15] Vitest Installed..."
if grep -q "vitest" package.json; then
  echo "  ✅ PASS"
else
  echo "  ❌ FAIL: vitest not in package.json"
  FAILURES=$((FAILURES + 1))
fi

# Gate 12: Verify Script Lengkap (BARU v2.0)
echo "[Gate 12/15] Verify Script Lengkap..."
SCRIPT_LINES=$(wc -l < verify_contract.sh)
if [ "$SCRIPT_LINES" -ge 80 ]; then
  echo "  ✅ PASS ($SCRIPT_LINES lines)"
else
  echo "  ❌ FAIL: verify_contract.sh only $SCRIPT_LINES lines (need >= 80)"
  FAILURES=$((FAILURES + 1))
fi

# Gate 13: No Trivial Test (BARU v2.0)
echo "[Gate 13/15] No Trivial Test..."
TRIVIAL_HITS=$(grep -rE 'expect\(1\)\.toBe\(1\)|expect\(true\)\.toBe\(true\)' tests/ | wc -l)
if [ "$TRIVIAL_HITS" -eq 0 ]; then
  echo "  ✅ PASS (no trivial tests)"
else
  echo "  ❌ FAIL: $TRIVIAL_HITS trivial tests found"
  FAILURES=$((FAILURES + 1))
fi

# Gate 14: Native Kotlin Modified (BARU v2.0)
echo "[Gate 14/15] Native Kotlin Modified..."
KT_MODIFIED=$(git log --name-only -1 | grep '\.kt$' | wc -l)
if [ "$KT_MODIFIED" -ge 1 ]; then
  echo "  ✅ PASS ($KT_MODIFIED Kotlin files modified in last commit)"
else
  echo "  ⚠️ WARN: No Kotlin files modified in last commit (OK if not working on native bugs)"
fi

# Gate 15: APK Build
echo "[Gate 15/15] APK Build..."
if cd android && ./gradlew assembleDebug 2>&1 | tee /tmp/apk.log | grep -q "BUILD SUCCESSFUL"; then
  echo "  ✅ PASS"
  cd ..
else
  echo "  ❌ FAIL: APK build failed"
  cd ..
  FAILURES=$((FAILURES + 1))
fi

echo ""
echo "================================================"
echo "  VERIFICATION SUMMARY"
echo "================================================"
if [ "$FAILURES" -eq 0 ]; then
  echo "  ✅ ALL GATES PASSED"
  echo "================================================"
  exit 0
else
  echo "  ❌ $FAILURES GATE(S) FAILED"
  echo "================================================"
  exit 1
fi
