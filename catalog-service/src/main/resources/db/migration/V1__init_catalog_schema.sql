CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price NUMERIC(10, 2) NOT NULL,
    category VARCHAR(100),
    image_url TEXT,
    seller_id BIGINT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_catalog_product_sku ON products(sku);
CREATE INDEX IF NOT EXISTS idx_catalog_product_category ON products(category);
CREATE INDEX IF NOT EXISTS idx_catalog_product_seller ON products(seller_id);

CREATE TABLE IF NOT EXISTS product_variants (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    sku VARCHAR(100) NOT NULL UNIQUE,
    title VARCHAR(150) NOT NULL,
    price NUMERIC(10, 2),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    image_url TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_catalog_variant_product ON product_variants(product_id);
CREATE INDEX IF NOT EXISTS idx_catalog_variant_sku ON product_variants(sku);

CREATE TABLE IF NOT EXISTS variant_attributes (
    variant_id BIGINT NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    attribute_key VARCHAR(100) NOT NULL,
    attribute_value VARCHAR(255),
    PRIMARY KEY (variant_id, attribute_key)
);
