# GEM-02: Exposure of app data keys

File: `server.ts:48-105`

Diff: Separated `ADMIN_TOKEN` parsing from `DATA_ENCRYPTION_KEY`.

Command Output:
```
node -e "..."
Passed
```

Description: Updated the app data to be encrypted by a completely separate `DATA_ENCRYPTION_KEY` located in a new file, and removed git tracking of `.data_key` making sure even if admin token is known, it cannot decrypt `app_data.json`.
