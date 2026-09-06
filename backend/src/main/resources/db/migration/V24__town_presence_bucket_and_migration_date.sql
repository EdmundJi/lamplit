-- V23 has already shipped. Keep its checksum and evolve the schema additively.
-- Historical samples may contain several accepted reports in one minute. Keep every row;
-- only the earliest one claims the minute bucket. Legacy duplicates retain a NULL bucket,
-- while new reports always supply a bucket and are deduplicated by this unique index.
ALTER TABLE town_presence_sample ADD COLUMN sample_minute BIGINT NULL;
UPDATE town_presence_sample p
JOIN (
    SELECT * FROM (
        SELECT user_id, scene, MIN(sampled_at) AS first_at,
               TIMESTAMPDIFF(MINUTE, '1970-01-01 00:00:00', sampled_at) AS minute_bucket
        FROM town_presence_sample
        GROUP BY user_id, scene, TIMESTAMPDIFF(MINUTE, '1970-01-01 00:00:00', sampled_at)
    ) historical_samples
) b ON b.user_id=p.user_id AND b.scene=p.scene AND b.first_at=p.sampled_at
SET p.sample_minute=b.minute_bucket;
ALTER TABLE town_presence_sample
    ADD UNIQUE KEY uk_town_sample_minute(user_id, sample_minute, scene);

-- New exchanges set this to the destination's local calendar date. Older records retain
-- their original calendar interpretation; the arrival slot is paired_with_npc_code.
ALTER TABLE town_migration ADD COLUMN local_date DATE NULL;
UPDATE town_migration SET local_date=DATE(moved_at) WHERE local_date IS NULL;
