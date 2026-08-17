-- Resolve any pre-existing duplicate ACTIVE versions (dev/E2E data) before enforcing uniqueness.
-- Keeps the highest semantic version per capability; deprecates the rest.
UPDATE capability_version cv
SET status = 'DEPRECATED',
    deprecated_at = COALESCE(cv.deprecated_at, NOW())
WHERE cv.status = 'ACTIVE'
  AND cv.capability_version_id NOT IN (
    SELECT DISTINCT ON (capability_id) capability_version_id
    FROM capability_version
    WHERE status = 'ACTIVE'
    ORDER BY capability_id,
             (split_part(version, '.', 1))::int DESC,
             COALESCE(NULLIF(split_part(version, '.', 2), ''), '0')::int DESC,
             COALESCE(NULLIF(split_part(version, '.', 3), ''), '0')::int DESC,
             created_at DESC
  );

CREATE UNIQUE INDEX uk_capability_version_one_active_per_capability
    ON capability_version (capability_id)
    WHERE status = 'ACTIVE';
