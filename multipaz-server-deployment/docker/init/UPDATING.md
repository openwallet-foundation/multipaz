# Updating initial data

This folder contains initial/seed data. Right now we only use it
for the System of Records (`records.json`).

## System of Records data

If you want to update this file, make sure Multipaz services are running (e.g. locally
on `localhost:8000` and use this command:
```bash
curl http://localhost:8000/records/identity/dump > records.json
```
This will dump the current content of the System of Record. Note that "Utopia Id" field
is currently automatically assigned by every system of record and this is NOT preserved.

To merge a System of Record dump into a (different) running System of Record do this:
```bash
(
  echo '{'
  echo '"password": "<your password>",'
  echo '"identities":'
  cat records-to-merge.json
  echo '}'
) | curl -H "Content-Type: application/json" -d @- http://localhost:8000/records/identity/load
```