-- V13: Heatmap route storage (ADR-0007).
--
-- One heatmap_route row per Activity with a route: the route in Web Mercator, split at GPS jumps and simplified
-- once per zoom band, plus copies of the Activity's athlete, sport and start time so tile queries read one table.
-- heatmap_route_geometries() is the single source of truth for splitting and simplification; the Activity Details
-- upsert and this backfill both call it. If its tolerances change, a new migration recomputes every row.

-- Every write stamps the row with a fresh value. A sequence takes no lock, unlike a counter on app_user would
-- (see ADR-0006 and ADR-0007), and the athlete's heatmap revision is (row count, highest stamp).
CREATE SEQUENCE heatmap_route_stamp_seq;

CREATE TABLE heatmap_route (
    activity_id BIGINT PRIMARY KEY REFERENCES activity(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    sport VARCHAR(255) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    stamp BIGINT NOT NULL DEFAULT nextval('heatmap_route_stamp_seq'),
    -- One geometry per zoom band: z0-5, z6-9, z10-12, and z13 and above.
    geom_z0 geometry(MultiLineString, 3857) NOT NULL,
    geom_z6 geometry(MultiLineString, 3857) NOT NULL,
    geom_z10 geometry(MultiLineString, 3857) NOT NULL,
    geom_z13 geometry(MultiLineString, 3857) NOT NULL
);

-- The heatmap revision is read from this index alone.
CREATE INDEX idx_heatmap_route_user_stamp ON heatmap_route (user_id, stamp);
-- Tiles find routes by the full-detail band's bounding box, which contains every coarser band's.
CREATE INDEX idx_heatmap_route_geom ON heatmap_route USING GIST (geom_z13);

-- Derives a heatmap route's geometries from an Activity's route (EPSG:4326) and sport.
--
-- 1. The route is split wherever consecutive points are further apart on the ground than the sport's GPS-jump
--    threshold, so a glitch is never drawn as a straight line across the map. Pieces of one point are dropped.
-- 2. The pieces are converted to Web Mercator and simplified once per zoom band, to about one screen pixel at
--    the band's most detailed zoom, keeping pieces that collapse so a short Activity still shows.
--
-- Returns no row when nothing of the route survives the split.
CREATE FUNCTION heatmap_route_geometries(route geometry, sport text)
RETURNS TABLE (geom_z0 geometry, geom_z6 geometry, geom_z10 geometry, geom_z13 geometry)
LANGUAGE sql STABLE STRICT PARALLEL SAFE
AS $$
    WITH points AS (
        SELECT (dp).path[1] AS i, (dp).geom AS p
        FROM ST_DumpPoints(route) AS dp
    ),
    steps AS (
        SELECT i, p,
               CASE
                   WHEN lag(p) OVER w IS NULL THEN 1
                   WHEN ST_DistanceSphere(lag(p) OVER w, p) > CASE sport WHEN 'RIDE' THEN 1000 ELSE 200 END THEN 1
                   ELSE 0
               END AS starts_piece
        FROM points
        WINDOW w AS (ORDER BY i)
    ),
    pieces AS (
        SELECT ST_MakeLine(p ORDER BY i) AS line
        FROM (SELECT i, p, sum(starts_piece) OVER (ORDER BY i) AS piece FROM steps) AS numbered
        GROUP BY piece
        HAVING count(*) >= 2
    ),
    merc AS (
        SELECT ST_Transform(ST_SetSRID(ST_Multi(ST_Collect(line)), 4326), 3857) AS g
        FROM pieces
    )
    SELECT ST_Multi(ST_Simplify(g, 2446, true)),
           ST_Multi(ST_Simplify(g, 153, true)),
           ST_Multi(ST_Simplify(g, 19, true)),
           ST_Multi(ST_Simplify(g, 1.2, true))
    FROM merc
    WHERE g IS NOT NULL
$$;

-- Backfill: every Activity that already has a route.
INSERT INTO heatmap_route (activity_id, user_id, sport, started_at, geom_z0, geom_z6, geom_z10, geom_z13)
SELECT a.id, a.user_id, a.sport, a.started_at, g.geom_z0, g.geom_z6, g.geom_z10, g.geom_z13
FROM activity_details d
JOIN activity a ON a.id = d.activity_id
CROSS JOIN LATERAL heatmap_route_geometries(d.route, a.sport) AS g;
