-- Run after Flyway on a development database or in a test transaction.
-- These accounts and image keys are fixtures for database tests.
INSERT INTO users (id, email, password_hash, display_name, status, email_verified_at)
VALUES ('10000000-0000-4000-8000-000000000001', 'seller1@example.test',
        'SEED_ONLY_NOT_FOR_LOGIN', 'Người bán demo', 'ACTIVE', now()),
       ('10000000-0000-4000-8000-000000000002', 'buyer1@example.test',
        'SEED_ONLY_NOT_FOR_LOGIN', 'Người mua demo', 'ACTIVE', now())
ON CONFLICT (id) DO NOTHING;

WITH demo_points AS (
    SELECT n,
           ST_SetSRID(ST_MakePoint(
               CASE WHEN n <= 10 THEN 106.8000 + ((n - 1) % 5 - 2) * 0.001
                    ELSE 106.8600 + ((n - 11) % 5) * 0.001 END,
               10.8700 + (((n - 1) % 10) / 5) * 0.001
           ), 4326)::geography AS exact_point
    FROM generate_series(1, 20) AS demo(n)
)
INSERT INTO listings (id, seller_id, category_id, title, description, price, condition,
                      exact_location, public_location, seller_score)
SELECT ('20000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       '10000000-0000-4000-8000-000000000001'::uuid,
       (SELECT id FROM categories WHERE code = 'ELECTRONICS'),
       'Tin mẫu ' || n, 'Dữ liệu giả để kiểm tra truy vấn theo bán kính.',
       100000 + n * 10000, 'LIKE_NEW', exact_point,
       ST_Project(exact_point, 300.0, radians(45.0)), 50
FROM demo_points
ON CONFLICT (id) DO NOTHING;

INSERT INTO listing_images (id, listing_id, object_key, position)
SELECT ('30000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       ('20000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       'demo/listings/tin_mau_' || n || '.jpg', 1
FROM generate_series(1, 20) AS demo(n)
ON CONFLICT (listing_id, position) DO NOTHING;
