-- V16: One stored band per zoom from z13 up, each simplified to about one pixel at its zoom (#119).
--
-- Until now the whole z13-16 band drew geom_z13, simplified to 1.2 m: about 1/8 of a pixel at z13, so the busiest
-- z13 and z14 tiles stroked many times the points their pixels can show. Each zoom from 13 to 16 now has its own
-- column, simplified to 40075016.686 / 2^z / 512 m (one pixel of a 512-pixel tile at zoom z):
--
--   geom_z13  9.55 m   (new meaning)
--   geom_z14  4.78 m   (new)
--   geom_z15  2.39 m   (new)
--   geom_z16  1.2 m    (today's geom_z13, renamed: still the full-detail band)
--
-- The z0-12 bands, their tolerances and the GPS-jump thresholds are unchanged, so V15's Full Heat segments, which are
-- generated from geom_z0, geom_z6 and geom_z10, keep their values. idx_heatmap_route_geom follows the rename and stays
-- the bounding-box index on the full-detail band.
--
-- Every row is recomputed from its Activity's route through the new heatmap_route_geometries (ADR-0007), so existing
-- rows match new writes exactly. stamp is left as it is: tile URLs change with the renderer version bump that ships
-- with this, and the Full Heat cache is in memory. The full-table UPDATE leaves dead tuples until autovacuum runs.

ALTER TABLE heatmap_route RENAME COLUMN geom_z13 TO geom_z16;

ALTER TABLE heatmap_route
    ADD COLUMN geom_z13 geometry(MultiLineString, 3857),
    ADD COLUMN geom_z14 geometry(MultiLineString, 3857),
    ADD COLUMN geom_z15 geometry(MultiLineString, 3857);

-- Its result columns change, so it is dropped and recreated rather than replaced.
DROP FUNCTION heatmap_route_geometries(geometry, text);

-- Derives a heatmap route's geometries from an Activity's route (EPSG:4326) and sport.
--
-- 1. The route is split wherever consecutive points are further apart on the ground than the sport's GPS-jump
--    threshold, so a glitch is never drawn as a straight line across the map. Pieces of one point are dropped.
-- 2. The pieces are converted to Web Mercator and simplified once per zoom band, to about one screen pixel at
--    the band's most detailed zoom, keeping pieces that collapse so a short Activity still shows.
--
-- Returns no row when nothing of the route survives the split.
CREATE FUNCTION heatmap_route_geometries(route geometry, sport text)
RETURNS TABLE (
    geom_z0 geometry, geom_z6 geometry, geom_z10 geometry,
    geom_z13 geometry, geom_z14 geometry, geom_z15 geometry, geom_z16 geometry
)
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
           ST_Multi(ST_Simplify(g, 9.55, true)),
           ST_Multi(ST_Simplify(g, 4.78, true)),
           ST_Multi(ST_Simplify(g, 2.39, true)),
           ST_Multi(ST_Simplify(g, 1.2, true))
    FROM merc
    WHERE g IS NOT NULL
$$;

-- Recompute every row from its Activity's route and sport, as the Activity Details upsert writes it. Every row the
-- application wrote came through the same split, so the function returns a row for each.
UPDATE heatmap_route h
SET geom_z0 = g.geom_z0,
    geom_z6 = g.geom_z6,
    geom_z10 = g.geom_z10,
    geom_z13 = g.geom_z13,
    geom_z14 = g.geom_z14,
    geom_z15 = g.geom_z15,
    geom_z16 = g.geom_z16
FROM activity_details d
JOIN activity a ON a.id = d.activity_id
CROSS JOIN LATERAL heatmap_route_geometries(d.route, a.sport) AS g
WHERE d.activity_id = h.activity_id;

-- A row the UPDATE did not reach has no Activity Details to recompute from. The application never writes one, but
-- hand-written SQL can (#124). Drop it, as the Activity Details upsert does when recomputing yields nothing, rather
-- than fail the NOT NULL below and stop the Server from starting.
DELETE FROM heatmap_route WHERE geom_z13 IS NULL;

ALTER TABLE heatmap_route
    ALTER COLUMN geom_z13 SET NOT NULL,
    ALTER COLUMN geom_z14 SET NOT NULL,
    ALTER COLUMN geom_z15 SET NOT NULL;
