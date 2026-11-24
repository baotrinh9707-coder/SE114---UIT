-- Run after Flyway on a development database or in a test transaction.
-- These accounts and image keys are fixtures for database tests.
INSERT INTO users (id, email, password_hash, display_name, status, email_verified_at)
VALUES ('10000000-0000-4000-8000-000000000001', 'seller1@example.test',
        'SEED_ONLY_NOT_FOR_LOGIN', 'Người bán demo', 'ACTIVE', now()),
       ('10000000-0000-4000-8000-000000000002', 'buyer1@example.test',
        'SEED_ONLY_NOT_FOR_LOGIN', 'Người mua demo', 'ACTIVE', now())
ON CONFLICT (id) DO NOTHING;

WITH area_points AS (
    SELECT slot, ST_Transform(ST_SetSRID(ST_MakePoint(longitude, 10.8700), 4326), 3857) AS point
    FROM (VALUES (1, 106.8000), (2, 106.8600)) AS demo(slot, longitude)
), area_cells AS (
    SELECT slot, floor(ST_X(point) / 300)::bigint AS grid_column,
                 floor(ST_Y(point) / 300)::bigint AS grid_row
    FROM area_points
)
INSERT INTO user_areas (id, user_id, slot, label, center, grid_cell, is_active,
                        status, verified_at, expires_at)
SELECT ('40000000-0000-4000-8000-' || lpad(slot::text, 12, '0'))::uuid,
       '10000000-0000-4000-8000-000000000001'::uuid,
       slot, CASE WHEN slot = 1 THEN 'Khu vực gần' ELSE 'Khu vực xa' END,
       ST_Transform(ST_SetSRID(ST_MakePoint((grid_column + 0.5) * 300, (grid_row + 0.5) * 300),
                              3857), 4326)::geography,
       concat_ws(':', 300, grid_column, grid_row), slot = 1, 'VERIFIED', now(), now() + interval '30 days'
FROM area_cells
ON CONFLICT (id) DO NOTHING;

WITH demo_points AS (
    SELECT n,
           ST_SetSRID(ST_MakePoint(
               CASE WHEN n <= 10 THEN 106.8000 + ((n - 1) % 5 - 2) * 0.001
                    ELSE 106.8600 + ((n - 11) % 5) * 0.001 END,
               10.8700 + (((n - 1) % 10) / 5) * 0.001
           ), 4326) AS exact_point
    FROM generate_series(1, 20) AS demo(n)
)
INSERT INTO listings (id, seller_id, category_id, area_id, title, description, price, condition,
                      status, exact_location, public_location, seller_score, published_at)
SELECT ('20000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       '10000000-0000-4000-8000-000000000001'::uuid,
       (SELECT id FROM categories WHERE code = 'ELECTRONICS'),
       CASE WHEN n <= 10 THEN '40000000-0000-4000-8000-000000000001'::uuid
            ELSE '40000000-0000-4000-8000-000000000002'::uuid END,
       'Tin mẫu ' || n, 'Dữ liệu giả để kiểm tra truy vấn theo bán kính.',
       100000 + n * 10000, 'LIKE_NEW', 'ACTIVE', exact_point::geography,
       ST_Transform(ST_SnapToGrid(ST_Transform(exact_point, 3857), 150, 150, 300, 300), 4326)::geography,
       50, now()
FROM demo_points
ON CONFLICT (id) DO NOTHING;

INSERT INTO listing_images (id, listing_id, owner_id, original_key, thumb_key, large_key,
                            position, processing_status)
SELECT ('30000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       ('20000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       '10000000-0000-4000-8000-000000000001'::uuid,
       'incoming/demo/tin_mau_' || n || '.jpg',
       'demo/listings/tin_mau_' || n || '_thumb.webp',
       'demo/listings/tin_mau_' || n || '_large.webp', 1, 'READY'
FROM generate_series(1, 20) AS demo(n)
ON CONFLICT (listing_id, position) DO NOTHING;
