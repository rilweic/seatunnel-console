-- Runs once when the MySQL volume is first created. Tables are managed by Flyway in console-server.
CREATE DATABASE IF NOT EXISTS `seatunnel_console` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'console'@'%' IDENTIFIED BY 'console';
GRANT ALL PRIVILEGES ON `seatunnel_console`.* TO 'console'@'%';
FLUSH PRIVILEGES;
