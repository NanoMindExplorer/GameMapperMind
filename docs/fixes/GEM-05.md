# GEM-05: Trivial Assertion 

File: `tests/mapper.test.ts`

Diff: Replaced expectations logic into separate testable logic removing `expect(1).toBe(1)`.

Command Output:
```
npm test -- mapper
Passed 3 tests
```

Description: Fixed mock definitions making sure the test actually tests specific variables that mock the app flow rather than just trivial 1 = 1 mappings.
