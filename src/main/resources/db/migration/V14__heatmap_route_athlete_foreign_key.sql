-- A heatmap route row belongs to an existing athlete, as every other per-athlete table does (V6, V10), and
-- goes with them. Every existing row copies its Activity's athlete, so the constraint validates as is.
ALTER TABLE heatmap_route
    ADD CONSTRAINT fk_heatmap_route_app_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE;
