# Local dependencies

This topology supplies one local PostgreSQL container. It creates isolated `iam`,
`ums`, `ledger`, and `notification` databases with separate migration and runtime
roles; it is not connected to AWS and contains no production credentials.

1. Copy `.env.example` to `.env`, then replace every placeholder with a distinct
   local-only password. Do not commit `.env`.
2. Run `./bin/up.sh` from this directory. PostgreSQL is bound only to
   `127.0.0.1:54329`.
3. Run `./bin/test-backend.sh` to execute migration-backed backend tests. The
   script configures Testcontainers for a Colima Docker context when necessary.

`./bin/reset.sh` destroys only the `dutch-local` Compose volume and recreates the
container and its deterministic service databases. Use it when a fixture needs a
clean database; it does not access cloud resources.
