package com.orderflow.config;

import com.orderflow.model.Product;
import com.orderflow.model.ProductVariant;
import com.orderflow.repository.ProductRepository;
import com.orderflow.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogDataInitializer implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (productRepository.count() > 0) {
            log.info("Catalog database already contains {} products. Skipping seed.", productRepository.count());
            return;
        }

        log.info("Seeding initial product catalog and variants in catalog-service...");

        // 1. Mechanical RGB Keyboard
        seedProduct(
                "PROD-KB-01",
                "Mechanical RGB Gaming Keyboard",
                "Aircraft-grade aluminum chassis with hot-swappable switches and per-key customizable RGB lighting.",
                new BigDecimal("4999.00"),
                "Peripherals",
                "https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=800&q=80",
                1L,
                List.of(
                        new VariantSeed("PROD-KB-01-BRN", "Tactile Brown / Stealth Black", new BigDecimal("4999.00"), true,
                                Map.of("switch", "Brown (Tactile)", "color", "Stealth Black"),
                                "https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("PROD-KB-01-RED", "Linear Red / Arctic White", new BigDecimal("5499.00"), false,
                                Map.of("switch", "Red (Linear)", "color", "Arctic White"),
                                "https://images.unsplash.com/photo-1618384887929-16ec33fab9ef?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("PROD-KB-01-BLU", "Clicky Blue / Cyberpunk Edition", new BigDecimal("5999.00"), false,
                                Map.of("switch", "Blue (Clicky)", "color", "Cyberpunk Neon"),
                                "https://images.unsplash.com/photo-1595225476474-87563907a212?auto=format&fit=crop&w=800&q=80")
                )
        );

        // 2. 34-inch UltraWide Curved Monitor
        seedProduct(
                "PROD-MON-02",
                "34-inch UltraWide Curved Monitor",
                "1500R curvature, 144Hz high refresh rate, 1ms response time, and HDR400 immersive cinema aspect ratio.",
                new BigDecimal("29999.00"),
                "Displays",
                "https://images.unsplash.com/photo-1527443224154-c4a3942d3acf?auto=format&fit=crop&w=800&q=80",
                1L,
                List.of(
                        new VariantSeed("PROD-MON-02-144", "144Hz WQHD / Matte Black", new BigDecimal("29999.00"), true,
                                Map.of("refreshRate", "144Hz", "resolution", "WQHD (3440x1440)"),
                                "https://images.unsplash.com/photo-1527443224154-c4a3942d3acf?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("PROD-MON-02-240", "240Hz OLED / Storm Gray", new BigDecimal("39999.00"), false,
                                Map.of("refreshRate", "240Hz", "panel", "OLED Quantum Dot"),
                                "https://images.unsplash.com/photo-1547119957-637f8679db1e?auto=format&fit=crop&w=800&q=80")
                )
        );

        // 3. Wireless ANC Studio Headphones
        seedProduct(
                "PROD-AUDIO-03",
                "Wireless ANC Studio Headphones",
                "Adaptive active noise cancellation, 40-hour ultra battery life, custom 40mm titanium drivers.",
                new BigDecimal("12999.00"),
                "Audio",
                "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=800&q=80",
                1L,
                List.of(
                        new VariantSeed("PROD-AUDIO-03-BLK", "Midnight Obsidian / Memory Foam", new BigDecimal("12999.00"), true,
                                Map.of("color", "Midnight Obsidian", "connectivity", "Bluetooth 5.3 + 3.5mm"),
                                "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("PROD-AUDIO-03-SLV", "Silver Birch / Brushed Aluminum", new BigDecimal("13499.00"), false,
                                Map.of("color", "Silver Birch", "connectivity", "Bluetooth 5.3 + 3.5mm"),
                                "https://images.unsplash.com/photo-1484704849700-f032a568e944?auto=format&fit=crop&w=800&q=80")
                )
        );

        // 4. Ergonomic Mesh Task Chair
        seedProduct(
                "PROD-CHR-04",
                "Ergonomic Mesh Task Chair",
                "Breathable elastomeric mesh, 4D adjustable armrests, and dynamic self-adjusting lumbar support.",
                new BigDecimal("18499.00"),
                "Furniture",
                "https://images.unsplash.com/photo-1580481077197-0f81d182284c?auto=format&fit=crop&w=800&q=80",
                1L,
                List.of(
                        new VariantSeed("PROD-CHR-04-BLK", "Standard Mesh / Charcoal Black", new BigDecimal("18499.00"), true,
                                Map.of("material", "Breathable Mesh", "color", "Charcoal Black"),
                                "https://images.unsplash.com/photo-1580481077197-0f81d182284c?auto=format&fit=crop&w=800&q=80")
                )
        );

        log.info("Catalog seeding finished successfully. Seeded {} products.", productRepository.count());
    }

    private void seedProduct(String sku, String name, String desc, BigDecimal price, String category,
                             String imageUrl, Long sellerId, List<VariantSeed> variants) {
        Product product = Product.builder()
                .sku(sku)
                .name(name)
                .description(desc)
                .price(price)
                .category(category)
                .imageUrl(imageUrl)
                .sellerId(sellerId)
                .build();

        Product saved = productRepository.save(product);

        for (VariantSeed vs : variants) {
            ProductVariant variant = ProductVariant.builder()
                    .product(saved)
                    .sku(vs.sku)
                    .title(vs.title)
                    .price(vs.price)
                    .isDefault(vs.isDefault)
                    .imageUrl(vs.imageUrl)
                    .active(true)
                    .attributes(vs.attributes)
                    .build();
            saved.addVariant(variant);
        }

        productRepository.save(saved);
    }

    private record VariantSeed(
            String sku,
            String title,
            BigDecimal price,
            boolean isDefault,
            Map<String, String> attributes,
            String imageUrl
    ) {}
}
