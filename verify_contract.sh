#!/bin/bash
# verify_contract.sh - Run after every AI task set
echo "=== Contract Verification Gate ==="
npm run lint 2>&1 | tee /tmp/lint.log
# mock detector
MOCK_HITS=$(grep -rE "\b(mock|stub|fake)\b" src/ --include="*.ts" --include="*.tsx" | grep -v ".test." | grep -v ".spec." | wc -l)
test $MOCK_HITS -eq 0 || { echo "FAIL: mock matches"; exit 1; }
# ... other checks
echo "=== ALL GATES PASSED ==="
exit 0
