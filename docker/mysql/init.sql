-- =============================================================================
-- Global Account Platform — MySQL Initialization Script
-- =============================================================================
-- This script runs once when the MySQL container is first created.
-- It creates 4 databases and corresponding service users.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Databases
-- ---------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS `auth_db`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `account_db`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `security_db`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `admin_db`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- Service Users (least-privilege for each service)
-- ---------------------------------------------------------------------------

-- auth-service user
CREATE USER IF NOT EXISTS 'auth_user'@'%' IDENTIFIED BY 'auth_pass';
GRANT ALL PRIVILEGES ON `auth_db`.* TO 'auth_user'@'%';

-- account-service user
CREATE USER IF NOT EXISTS 'account_user'@'%' IDENTIFIED BY 'account_pass';
GRANT ALL PRIVILEGES ON `account_db`.* TO 'account_user'@'%';

-- security-service user
CREATE USER IF NOT EXISTS 'security_user'@'%' IDENTIFIED BY 'security_pass';
GRANT ALL PRIVILEGES ON `security_db`.* TO 'security_user'@'%';

-- admin-service user
CREATE USER IF NOT EXISTS 'admin_user'@'%' IDENTIFIED BY 'admin_pass';
GRANT ALL PRIVILEGES ON `admin_db`.* TO 'admin_user'@'%';

FLUSH PRIVILEGES;
