# GEM-07: MacroService missing localstorage

File: `src/services/MacroService.ts:17-25`

Diff: Add a fallback Error throw if the key does not exist.

Command Output:
```
npm test -- macro
Passed
```

Description: Replaced fallback static string from macro builder with proper throwing preventing arbitrary invalid calls.
