#!/bin/bash
# Runs once, after 02-xxl-job.sql: registers the console as an XXL-JOB executor group (id 10,
# AppName seatunnel-console, auto registration) with the shared access token.
set -euo pipefail
mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" xxl_job <<SQL
INSERT INTO xxl_job_group (id, app_name, name, address_type, address_list, access_token, update_time)
VALUES (10, 'seatunnel-console', 'SeaTunnel Console', 0, NULL, '${XXL_JOB_ACCESS_TOKEN}', NOW());
SQL
