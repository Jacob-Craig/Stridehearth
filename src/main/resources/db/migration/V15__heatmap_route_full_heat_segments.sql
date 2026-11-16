-- V15: The snapped segments of each heatmap route, stored so the Full Heat is counted without geometry work
-- (ADR-0008).
--
-- The Full Heat counts, for each segment of a coarse grid, how many distinct Activities pass along it. Snapping
-- 10,000 routes to the grid on every count took 1 to 9 seconds, so each route's segments are now worked out once,
-- when the row is written, and stored as plain numbers per zoom band the Full Heat is measured on (z0-5, z6-9,
-- z10-12). Counting is then a GROUP BY over those numbers.
--
-- Each band's segments are split in two by a fixed hash of the segment: about one in eight are "sampled" and the
-- rest are not. A segment is sampled for every route or for none, so the sampled segments carry exact counts and
-- their 95th percentile estimates the whole Heatmap's. FullHeat counts a large Heatmap on the sample alone and a
-- small one on both halves.
--
-- The columns are generated, so every write of a route's geometries (the Activity Details upsert, a backfill, or
-- SQL seeding) keeps them in step, and adding them here fills them for every existing row. If the grid or the
-- sample changes, a new migration drops and re-adds the columns.

-- A route's distinct snapped segments on the Full Heat grid for a zoom band whose most detailed zoom is max_zoom,
-- either the sampled ones or the rest.
--
-- 1. The grid is 4 screen pixels at max_zoom, in Web Mercator metres, and never finer than 10 m on the ground at
--    80 degrees of latitude (about 58 Mercator metres). The finest band's grid is about 76 m, so that floor never
--    binds today; it guards a finer band added later. One grid covers the whole Heatmap, so segments of different
--    routes meet wherever they share a road.
-- 2. The route is cut into pieces no longer than a cell and each point is snapped to its grid cell, so passes
--    along a road meet on the same segments however their GPS points fell. A step that stays in one cell is not a
--    segment.
-- 3. A segment is numbered from the end cell that sorts first (by x, then y) and the step to the other end, so
--    passes in either direction get the same number. Cell indices fit in 21 bits each at the floor grid and a step is at most 2 cells either
--    way, so the number is exact, never a hash:
--        (x + 2^20) << 27 | (y + 2^20) << 6 | (dx + 2) << 3 | (dy + 2)
-- 4. A segment is sampled when the low three bits of hashint8extended(segment, 0) are zero. That hash is fixed
--    across PostgreSQL versions, as hash partitioning relies on it.
CREATE FUNCTION heatmap_route_segments(route geometry, max_zoom integer, sampled boolean)
RETURNS bigint[]
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    WITH grid AS (
        SELECT greatest(4 * 40075016.686 / (512::float8 * (1 << max_zoom)), 10 / cos(radians(80))) AS cell
    ),
    points AS (
        SELECT dp.path[1] AS line, dp.path[2] AS i,
               round(ST_X(dp.geom) / grid.cell)::bigint AS x,
               round(ST_Y(dp.geom) / grid.cell)::bigint AS y
        FROM grid, ST_DumpPoints(ST_Segmentize(route, grid.cell)) AS dp
    ),
    steps AS (
        SELECT lag(x) OVER w AS ax, lag(y) OVER w AS ay, x AS bx, y AS by
        FROM points
        WINDOW w AS (PARTITION BY line ORDER BY i)
    ),
    segments AS (
        SELECT DISTINCT
               -- PostgreSQL gives << and | the same precedence, hence the brackets.
               ((CASE WHEN forward THEN ax ELSE bx END + 1048576) << 27)
             | ((CASE WHEN forward THEN ay ELSE by END + 1048576) << 6)
             | ((CASE WHEN forward THEN bx - ax ELSE ax - bx END + 2) << 3)
             | (CASE WHEN forward THEN by - ay ELSE ay - by END + 2) AS segment
        FROM (SELECT *, (ax, ay) < (bx, by) AS forward FROM steps WHERE (ax, ay) <> (bx, by)) AS stepped
    )
    SELECT ARRAY(
        SELECT segment FROM segments
        WHERE (hashint8extended(segment, 0) & 7 = 0) = sampled
        ORDER BY segment
    )
$$;

ALTER TABLE heatmap_route
    ADD COLUMN heat_segments_z0_sampled bigint[] GENERATED ALWAYS AS (heatmap_route_segments(geom_z0, 5, true)) STORED,
    ADD COLUMN heat_segments_z0_rest bigint[] GENERATED ALWAYS AS (heatmap_route_segments(geom_z0, 5, false)) STORED,
    ADD COLUMN heat_segments_z6_sampled bigint[] GENERATED ALWAYS AS (heatmap_route_segments(geom_z6, 9, true)) STORED,
    ADD COLUMN heat_segments_z6_rest bigint[] GENERATED ALWAYS AS (heatmap_route_segments(geom_z6, 9, false)) STORED,
    ADD COLUMN heat_segments_z10_sampled bigint[] GENERATED ALWAYS AS (heatmap_route_segments(geom_z10, 12, true)) STORED,
    ADD COLUMN heat_segments_z10_rest bigint[] GENERATED ALWAYS AS (heatmap_route_segments(geom_z10, 12, false)) STORED;
