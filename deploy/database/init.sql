-- =============================================================================
-- OpenWare platform — one-shot database initialization
--
-- Fresh MySQL 8.0 instance only. Fully idempotent (safe to re-run).
-- After this script, just start the services: each service runs Flyway on boot
-- and creates/migrates ALL tables automatically — no other SQL is required.
--
-- Two schemas:
--   open_im   — business data (all im-* / platform-* / common-* services)
--   open_audit  — independent audit-log schema (enabled via `audit-schema` profile)
--
-- Usage:
--   1. docker compose (recommended): this file is mounted into
--      /docker-entrypoint-initdb.d/ and runs automatically on first boot.
--   2. Manual:
--        mysql -h127.0.0.1 -P13306 -uroot -p < deploy/database/init.sql
--
-- Security: replace 'change-me' with a strong password for any real deployment,
-- or override via the DB_USERNAME/DB_PASSWORD environment variables used by
-- docker-compose / k8s manifests.
-- =============================================================================

SET NAMES utf8mb4;

-- 1. Schemas -------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS open_im
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS open_audit
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 2. Application user -----------------------------------------------------------
-- '%' so it works from containers / host / k8s pod networks interchangeably.
CREATE USER IF NOT EXISTS 'im_user'@'%' IDENTIFIED BY 'change-me';
ALTER USER 'im_user'@'%' IDENTIFIED BY 'change-me';

-- 3. Grants --------------------------------------------------------------------
GRANT ALL PRIVILEGES ON open_im.* TO 'im_user'@'%';
GRANT ALL PRIVILEGES ON open_audit.* TO 'im_user'@'%';
FLUSH PRIVILEGES;
