-- Connector option definitions are read live from the cluster's /option-rules (docs/design.md §7.1),
-- so the uploaded metadata table planned in V1 was never used.
DROP TABLE IF EXISTS seatunnel_metadata;
