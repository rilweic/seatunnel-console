#!/bin/bash
# Runs once, when the MySQL volume is first created. Tables are managed by Flyway in console-server.
set -euo pipefail
mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS \`seatunnel_console\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'console'@'%' IDENTIFIED BY '${CONSOLE_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`seatunnel_console\`.* TO 'console'@'%';
FLUSH PRIVILEGES;
SQL
