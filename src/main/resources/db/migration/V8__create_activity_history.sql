CREATE TABLE activity_history (
    id BIGSERIAL PRIMARY KEY,
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    change_type VARCHAR(32) NOT NULL,
    description TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_activity_history_activity_id ON activity_history(activity_id, created_at DESC);

-- Backfill initial CREATED entry for pre-existing activities
INSERT INTO activity_history (activity_id, change_type, description, created_at)
SELECT
    a.id,
    'CREATED',
    CASE
        WHEN a.source = 'MANUAL' THEN 'Logged manually'
        WHEN ad.parser_version IS NOT NULL AND COALESCE(lap_counts.cnt, 0) > 0 THEN
            'Imported from FIT file (Parser v' || ad.parser_version || ', ' || lap_counts.cnt || CASE WHEN lap_counts.cnt = 1 THEN ' lap)' ELSE ' laps)' END
        WHEN ad.parser_version IS NOT NULL THEN
            'Imported from FIT file (Parser v' || ad.parser_version || ')'
        WHEN COALESCE(lap_counts.cnt, 0) > 0 THEN
            'Imported from FIT file (' || lap_counts.cnt || CASE WHEN lap_counts.cnt = 1 THEN ' lap)' ELSE ' laps)' END
        ELSE
            'Imported from FIT file'
    END,
    a.started_at
FROM activity a
LEFT JOIN activity_details ad ON ad.activity_id = a.id
LEFT JOIN (
    SELECT activity_id, count(*) AS cnt
    FROM activity_laps
    GROUP BY activity_id
) lap_counts ON lap_counts.activity_id = a.id;
