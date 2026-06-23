# GEM-01: Admin Token Committed

File: `.admin_token` & `.gitignore`

Diff: Removed `.admin_token` tracking and added it to `.gitignore`.

Command Output:
```
ls -l .admin_token 
Removed from git via deleting the file temporarily and making it not exist, then let the server regenerate it.
```

Description: Fixed gitignore to not track `.admin_token` avoiding sensitive tokens checking.
