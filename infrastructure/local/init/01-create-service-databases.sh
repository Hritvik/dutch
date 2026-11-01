#!/usr/bin/env sh
set -eu

create_service_database() {
  service="$1"
  migration_password="$2"
  runtime_password="$3"
  database="dutch_${service}"
  migration_role="${service}_migration"
  runtime_role="${service}_runtime"

  psql --username "$POSTGRES_USER" --dbname postgres \
    --set database_name="$database" \
    --set migration_role="$migration_role" \
    --set migration_password="$migration_password" \
    --set runtime_role="$runtime_role" \
    --set runtime_password="$runtime_password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'migration_role', :'migration_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migration_role') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_role', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_role') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'database_name', :'migration_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database_name') \gexec
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'database_name') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', :'database_name', :'migration_role', :'runtime_role') \gexec
SQL

  psql --username "$POSTGRES_USER" --dbname "$database" \
    --set migration_role="$migration_role" \
    --set runtime_role="$runtime_role" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'migration_role') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'runtime_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'migration_role', :'runtime_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO %I', :'migration_role', :'runtime_role') \gexec
SQL
}

create_service_database iam "$DUTCH_LOCAL_IAM_MIGRATION_PASSWORD" "$DUTCH_LOCAL_IAM_RUNTIME_PASSWORD"
create_service_database ums "$DUTCH_LOCAL_UMS_MIGRATION_PASSWORD" "$DUTCH_LOCAL_UMS_RUNTIME_PASSWORD"
create_service_database ledger "$DUTCH_LOCAL_LEDGER_MIGRATION_PASSWORD" "$DUTCH_LOCAL_LEDGER_RUNTIME_PASSWORD"
create_service_database notification "$DUTCH_LOCAL_NOTIFICATION_MIGRATION_PASSWORD" "$DUTCH_LOCAL_NOTIFICATION_RUNTIME_PASSWORD"
