package com.orderflow.config;

import com.orderflow.model.*;
import com.orderflow.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final SellerRepository sellerRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final Map<String, String> SEED_IMAGES = Map.of(
            "PROD-KB-01", "https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=800&q=80",
            "PROD-MON-02", "https://images.unsplash.com/photo-1527443224154-c4a3942d3acf?auto=format&fit=crop&w=800&q=80",
            "PROD-AUDIO-03", "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=800&q=80",
            "PROD-CHAIR-04", "https://images.unsplash.com/photo-1580481077190-7361346d1807?auto=format&fit=crop&w=800&q=80"
    );

    @Override
    public void run(String... args) {
        // Synchronize users_role_check constraint for PostgreSQL to allow ROLE_SELLER
        try {
            jdbcTemplate.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check");
            jdbcTemplate.execute("ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ROLE_CUSTOMER', 'ROLE_ADMIN', 'ROLE_DELIVERY_AGENT', 'ROLE_SELLER'))");
        } catch (Exception e) {
            log.warn("Database constraint sync notice: {}", e.getMessage());
        }

        // Ensure image_url and description columns support arbitrary length URLs and markdown descriptions
        try {
            jdbcTemplate.execute("ALTER TABLE products ALTER COLUMN image_url TYPE TEXT");
            jdbcTemplate.execute("ALTER TABLE products ALTER COLUMN description TYPE TEXT");
            jdbcTemplate.execute("ALTER TABLE product_variants ALTER COLUMN image_url TYPE TEXT");
        } catch (Exception e) {
            log.warn("Database column type migration notice: {}", e.getMessage());
        }

        // Seed default Admin
        if (!userRepository.existsByEmail("admin@orderflow.io")) {
            User admin = User.builder()
                    .email("admin@orderflow.io")
                    .password(passwordEncoder.encode("AdminPassword123!"))
                    .fullName("Platform Administrator")
                    .role(Role.ROLE_ADMIN)
                    .build();
            userRepository.save(admin);
            log.info("Initialized default Admin user: admin@orderflow.io");
        }

        // Seed default Customer
        if (!userRepository.existsByEmail("customer@orderflow.io")) {
            User customer = User.builder()
                    .email("customer@orderflow.io")
                    .password(passwordEncoder.encode("CustomerPassword123!"))
                    .fullName("Jane Doe")
                    .role(Role.ROLE_CUSTOMER)
                    .build();
            userRepository.save(customer);
            log.info("Initialized default Customer user: customer@orderflow.io");
        }

        // Seed default Delivery Agent
        if (!userRepository.existsByEmail("agent@orderflow.io")) {
            User agent = User.builder()
                    .email("agent@orderflow.io")
                    .password(passwordEncoder.encode("AgentPassword123!"))
                    .fullName("Alex Rider (Fleet Agent)")
                    .role(Role.ROLE_DELIVERY_AGENT)
                    .build();
            userRepository.save(agent);
            log.info("Initialized default Delivery Agent user: agent@orderflow.io");
        }

        // Seed default Merchant / Vendor
        User merchant = userRepository.findByEmail("merchant@orderflow.io").orElseGet(() -> {
            User m = User.builder()
                    .email("merchant@orderflow.io")
                    .password(passwordEncoder.encode("MerchantPassword123!"))
                    .fullName("Apex Tech Merchant")
                    .role(Role.ROLE_SELLER)
                    .build();
            return userRepository.save(m);
        });

        Seller seller = sellerRepository.findByUserId(merchant.getId()).orElseGet(() -> {
            Seller s = Seller.builder()
                    .userId(merchant.getId())
                    .businessName("Apex Tech Store")
                    .contactEmail("merchant@orderflow.io")
                    .status("ACTIVE")
                    .build();
            return sellerRepository.save(s);
        });
        log.info("Initialized default Merchant user: merchant@orderflow.io (Seller ID: {})", seller.getId());

        // Seed sample products with images and variants
        seedProducts(seller.getId());

        // Auto-migrate any existing unlinked products/inventories/carts/orders to canonical variants
        migrateExistingProductsToVariants();
    }

    private void seedProducts(Long defaultSellerId) {
        // 1. Mechanical RGB Keyboard
        createProductWithVariants(
                "PROD-KB-01",
                "Mechanical RGB Gaming Keyboard",
                "Aircraft-grade aluminum chassis with hot-swappable switches and per-key customizable RGB lighting.",
                new BigDecimal("4999.00"),
                "Peripherals",
                SEED_IMAGES.get("PROD-KB-01"),
                defaultSellerId,
                List.of(
                        new VariantSeed("PROD-KB-01-BRN", "Tactile Brown / Stealth Black", new BigDecimal("4999.00"), true, 25,
                                Map.of("switch", "Brown (Tactile)", "color", "Stealth Black"), SEED_IMAGES.get("PROD-KB-01")),
                        new VariantSeed("PROD-KB-01-RED", "Linear Red / Arctic White", new BigDecimal("5499.00"), false, 15,
                                Map.of("switch", "Red (Linear)", "color", "Arctic White"), "https://images.unsplash.com/photo-1618384887929-16ec33fab9ef?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("PROD-KB-01-BLU", "Clicky Blue / Cyberpunk Edition", new BigDecimal("5999.00"), false, 10,
                                Map.of("switch", "Blue (Clicky)", "color", "Cyberpunk Neon"), "https://images.unsplash.com/photo-1595225476474-87563907a212?auto=format&fit=crop&w=800&q=80")
                )
        );

        // 2. 34-inch UltraWide Curved Monitor
        createProductWithVariants(
                "PROD-MON-02",
                "34-inch UltraWide Curved Monitor",
                "1500R curvature, 144Hz high refresh rate, 1ms response time, and HDR400 immersive cinema aspect ratio.",
                new BigDecimal("29999.00"),
                "Displays",
                SEED_IMAGES.get("PROD-MON-02"),
                defaultSellerId,
                List.of(
                        new VariantSeed("PROD-MON-02-144", "144Hz WQHD / Matte Black", new BigDecimal("29999.00"), true, 15,
                                Map.of("refreshRate", "144Hz", "resolution", "WQHD (3440x1440)"), SEED_IMAGES.get("PROD-MON-02")),
                        new VariantSeed("PROD-MON-02-240", "240Hz OLED / Storm Gray", new BigDecimal("39999.00"), false, 8,
                                Map.of("refreshRate", "240Hz", "panel", "OLED Quantum Dot"), "https://images.unsplash.com/photo-1547119957-637f8679db1e?auto=format&fit=crop&w=800&q=80")
                )
        );

        // 3. Wireless ANC Studio Headphones
        createProductWithVariants(
                "PROD-AUDIO-03",
                "Wireless ANC Studio Headphones",
                "Adaptive active noise cancellation, 40-hour ultra battery life, custom 40mm titanium drivers.",
                new BigDecimal("12999.00"),
                "Audio",
                SEED_IMAGES.get("PROD-AUDIO-03"),
                defaultSellerId,
                List.of(
                        new VariantSeed("PROD-AUDIO-03-BLK", "Midnight Onyx", new BigDecimal("12999.00"), true, 20,
                                Map.of("color", "Midnight Onyx", "finish", "Matte"), SEED_IMAGES.get("PROD-AUDIO-03")),
                        new VariantSeed("PROD-AUDIO-03-SLV", "Silver Mist", new BigDecimal("12999.00"), false, 15,
                                Map.of("color", "Silver Mist", "finish", "Brushed Aluminum"), "https://images.unsplash.com/photo-1484704849700-f032a568e944?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("PROD-AUDIO-03-GLD", "Rose Gold Edition", new BigDecimal("14999.00"), false, 10,
                                Map.of("color", "Rose Gold", "edition", "Special Edition"), "https://images.unsplash.com/photo-1546435770-a3e426bf472b?auto=format&fit=crop&w=800&q=80")
                )
        );

        // 4. Ergonomic Mesh Gaming Chair
        createProductWithVariants(
                "PROD-CHAIR-04",
                "Ergonomic Mesh Gaming Chair",
                "Reinforced breathable mesh, dynamic self-adjusting lumbar support, 4D armrests, and 135-degree recline.",
                new BigDecimal("15999.00"),
                "Furniture",
                SEED_IMAGES.get("PROD-CHAIR-04"),
                defaultSellerId,
                List.of(
                        new VariantSeed("PROD-CHAIR-04-BLK", "Carbon Black Mesh", new BigDecimal("15999.00"), true, 12,
                                Map.of("color", "Carbon Black", "material", "Breathable Mesh"), SEED_IMAGES.get("PROD-CHAIR-04")),
                        new VariantSeed("PROD-CHAIR-04-WHT", "Glacier White & Gray", new BigDecimal("17999.00"), false, 8,
                                Map.of("color", "Glacier White", "frame", "Polished Aluminum"), "https://images.unsplash.com/photo-1592078615290-033ee584e267?auto=format&fit=crop&w=800&q=80")
                )
        );

        // ==========================================
        // 5. COSMETICS
        // ==========================================
        createProductWithVariants(
                "COSM-SERUM-01",
                "24K Gold Radiance Face Serum",
                "Luxury anti-aging face serum enriched with 24K pure gold flakes, high-potency hyaluronic acid, and cold-pressed rosehip seed oil for a radiant, glass-skin glow.",
                new BigDecimal("1299.00"),
                "Cosmetics",
                "https://images.unsplash.com/photo-1620916566398-39f1143ab7be?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("COSM-SERUM-01-30ML", "Standard 30ml Dropper", new BigDecimal("1299.00"), true, 35,
                                Map.of("volume", "30ml", "type", "Dropper Bottle"), "https://images.unsplash.com/photo-1620916566398-39f1143ab7be?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("COSM-SERUM-01-50ML", "Deluxe 50ml Pump", new BigDecimal("1899.00"), false, 20,
                                Map.of("volume", "50ml", "type", "Airless Pump"), "https://images.unsplash.com/photo-1608248597359-009d17d5c7f8?auto=format&fit=crop&w=800&q=80")
                )
        );

        createProductWithVariants(
                "COSM-LIP-02",
                "Matte Velvet Liquid Lipstick Collection",
                "16-hour smudge-proof, transfer-resistant matte liquid lipstick infused with organic vitamin E and jojoba oil nourishment.",
                new BigDecimal("799.00"),
                "Cosmetics",
                "https://images.unsplash.com/photo-1586495777744-4413f21062fa?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("COSM-LIP-02-RED", "Ruby Crimson Matte", new BigDecimal("799.00"), true, 40,
                                Map.of("shade", "Ruby Crimson", "finish", "Ultra Matte"), "https://images.unsplash.com/photo-1586495777744-4413f21062fa?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("COSM-LIP-02-NUDE", "Rosewood Nude Velvet", new BigDecimal("799.00"), false, 30,
                                Map.of("shade", "Rosewood Nude", "finish", "Soft Velvet"), "https://images.unsplash.com/photo-1599733589046-10c005739ef9?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("COSM-LIP-02-PLUM", "Royal Plum Bold", new BigDecimal("849.00"), false, 25,
                                Map.of("shade", "Royal Plum", "finish", "Bold Matte"), "https://images.unsplash.com/photo-1571781926291-c477ebfd024b?auto=format&fit=crop&w=800&q=80")
                )
        );

        createProductWithVariants(
                "COSM-CREAM-03",
                "Hydrating Vitamin C Glow Day Cream",
                "Ultra-lightweight oil-free hydration cream powered by Kakadu plum Vitamin C, 2% niacinamide, and soothing green tea extracts.",
                new BigDecimal("899.00"),
                "Cosmetics",
                "https://images.unsplash.com/photo-1556228720-195a672e8a03?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("COSM-CREAM-03-50G", "50g Glass Jar", new BigDecimal("899.00"), true, 45,
                                Map.of("weight", "50g", "skinType", "All Skin Types"), "https://images.unsplash.com/photo-1556228720-195a672e8a03?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("COSM-CREAM-03-100G", "100g Value Tub", new BigDecimal("1499.00"), false, 25,
                                Map.of("weight", "100g", "skinType", "All Skin Types"), "https://images.unsplash.com/photo-1570172619644-dfd03ed5d881?auto=format&fit=crop&w=800&q=80")
                )
        );

        // ==========================================
        // 6. NAMKEENS (SAVORIES & SNACKS)
        // ==========================================
        createProductWithVariants(
                "NAMK-BHUJ-01",
                "Royal Bikaneri Crispy Bhujia Sev",
                "Crispy moth bean and chickpea bhujia blended with authentic Rajasthani spices, fragrant hing, and cold-pressed groundnut oil.",
                new BigDecimal("249.00"),
                "Namkeens",
                "https://images.unsplash.com/photo-1599488615731-7e5c2823ff28?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("NAMK-BHUJ-01-400G", "400g Fresh Pack", new BigDecimal("249.00"), true, 50,
                                Map.of("weight", "400g", "spiceLevel", "Medium Spicy"), "https://images.unsplash.com/photo-1599488615731-7e5c2823ff28?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("NAMK-BHUJ-01-1KG", "1kg Family Value Pack", new BigDecimal("549.00"), false, 30,
                                Map.of("weight", "1000g", "spiceLevel", "Medium Spicy"), "https://images.unsplash.com/photo-1567620832903-9fc6debc209f?auto=format&fit=crop&w=800&q=80")
                )
        );

        createProductWithVariants(
                "NAMK-SEV-02",
                "Spicy Ratlami Laung Clove Sev",
                "Classic Malwa recipe seasoned with pungent cloves, freshly crushed black pepper, and ajwain for an unmistakable spicy crunch.",
                new BigDecimal("219.00"),
                "Namkeens",
                "https://images.unsplash.com/photo-1601050690597-df0568f70950?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("NAMK-SEV-02-400G", "400g Zip Pouch", new BigDecimal("219.00"), true, 40,
                                Map.of("weight", "400g", "spiceLevel", "Hot & Spicy"), "https://images.unsplash.com/photo-1601050690597-df0568f70950?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("NAMK-SEV-02-800G", "800g Double Pack", new BigDecimal("399.00"), false, 25,
                                Map.of("weight", "800g", "spiceLevel", "Hot & Spicy"), "https://images.unsplash.com/photo-1567620832903-9fc6debc209f?auto=format&fit=crop&w=800&q=80")
                )
        );

        createProductWithVariants(
                "NAMK-KAJU-03",
                "Roasted Peri Peri & Salted Jumbo Cashews",
                "Premium W240 whole jumbo cashews slow-roasted in pure ghee and dusted with tangy peri peri spices and pink rock salt.",
                new BigDecimal("499.00"),
                "Namkeens",
                "https://images.unsplash.com/photo-1509358271058-acd22cc93898?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("NAMK-KAJU-03-PERI", "Spicy Peri Peri (250g)", new BigDecimal("499.00"), true, 35,
                                Map.of("flavor", "Peri Peri", "weight", "250g"), "https://images.unsplash.com/photo-1509358271058-acd22cc93898?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("NAMK-KAJU-03-SALT", "Himalayan Salted (250g)", new BigDecimal("499.00"), false, 30,
                                Map.of("flavor", "Himalayan Pink Salt", "weight", "250g"), "https://images.unsplash.com/photo-1536591375315-1b836814b732?auto=format&fit=crop&w=800&q=80")
                )
        );

        // ==========================================
        // 7. CLOTHES
        // ==========================================
        createProductWithVariants(
                "CLOTH-KURTA-01",
                "Artisanal Handblock Pure Cotton Kurta",
                "100% fine organic breathable cotton printed with handcrafted Bagru vegetable dye patterns, mandarin collar, and side slit silhouette.",
                new BigDecimal("1499.00"),
                "Clothes",
                "https://images.unsplash.com/photo-1583391733956-3750e0ff4e8b?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("CLOTH-KURTA-01-IND-M", "Indigo Blue / Size M", new BigDecimal("1499.00"), true, 25,
                                Map.of("size", "M", "color", "Indigo Blue", "fabric", "100% Organic Cotton"), "https://images.unsplash.com/photo-1583391733956-3750e0ff4e8b?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("CLOTH-KURTA-01-IND-L", "Indigo Blue / Size L", new BigDecimal("1499.00"), false, 30,
                                Map.of("size", "L", "color", "Indigo Blue", "fabric", "100% Organic Cotton"), "https://images.unsplash.com/photo-1583391733956-3750e0ff4e8b?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("CLOTH-KURTA-01-IND-XL", "Indigo Blue / Size XL", new BigDecimal("1599.00"), false, 20,
                                Map.of("size", "XL", "color", "Indigo Blue", "fabric", "100% Organic Cotton"), "https://images.unsplash.com/photo-1583391733956-3750e0ff4e8b?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("CLOTH-KURTA-01-WHT-L", "Ivory White / Size L", new BigDecimal("1499.00"), false, 22,
                                Map.of("size", "L", "color", "Ivory White", "fabric", "100% Organic Cotton"), "https://images.unsplash.com/photo-1602810318383-e386cc2a3ccf?auto=format&fit=crop&w=800&q=80")
                )
        );

        createProductWithVariants(
                "CLOTH-SAREE-02",
                "Heritage Banarasi Chanderi Silk Saree",
                "Woven by master weavers with lustrous gold zari border and traditional paisley booti motifs. Includes unstitched designer blouse fabric.",
                new BigDecimal("4499.00"),
                "Clothes",
                "https://images.unsplash.com/photo-1610030469983-98e550d6193c?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("CLOTH-SAREE-02-EMER", "Emerald Royal Green", new BigDecimal("4499.00"), true, 15,
                                Map.of("color", "Emerald Green", "fabric", "Chanderi Silk", "weave", "Zari Handloom"), "https://images.unsplash.com/photo-1610030469983-98e550d6193c?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("CLOTH-SAREE-02-MARO", "Deep Crimson Maroon", new BigDecimal("4799.00"), false, 12,
                                Map.of("color", "Crimson Maroon", "fabric", "Chanderi Silk", "weave", "Antique Zari"), "https://images.unsplash.com/photo-1583391733956-3750e0ff4e8b?auto=format&fit=crop&w=800&q=80")
                )
        );

        createProductWithVariants(
                "CLOTH-DENIM-03",
                "Vintage Washed Denim Oversized Jacket",
                "Heavyweight 14oz ring-spun durable denim with authentic stone wash distressing, antiqued brass hardware, and dual welt chest pockets.",
                new BigDecimal("2799.00"),
                "Clothes",
                "https://images.unsplash.com/photo-1544441893-675973e31985?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("CLOTH-DENIM-03-M", "Vintage Indigo / Size M", new BigDecimal("2799.00"), true, 25,
                                Map.of("size", "M", "fit", "Relaxed Oversized", "color", "Vintage Indigo"), "https://images.unsplash.com/photo-1544441893-675973e31985?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("CLOTH-DENIM-03-L", "Vintage Indigo / Size L", new BigDecimal("2799.00"), false, 30,
                                Map.of("size", "L", "fit", "Relaxed Oversized", "color", "Vintage Indigo"), "https://images.unsplash.com/photo-1544441893-675973e31985?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("CLOTH-DENIM-03-BLK-M", "Washed Charcoal / Size M", new BigDecimal("2999.00"), false, 18,
                                Map.of("size", "M", "fit", "Relaxed Oversized", "color", "Charcoal Black"), "https://images.unsplash.com/photo-1551028719-00167b16eac5?auto=format&fit=crop&w=800&q=80")
                )
        );

        // ==========================================
        // 8. JEWELLERY
        // ==========================================
        createProductWithVariants(
                "JEWL-CHOKER-01",
                "Kundan Polki Choker Necklace & Earring Set",
                "Royal uncut Kundan stones hand-embedded with dangling freshwater seed pearls and vibrant green Meenakari detailing on the reverse.",
                new BigDecimal("3499.00"),
                "Jewellery",
                "https://images.unsplash.com/photo-1599643478518-a784e5dc4c8f?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("JEWL-CHOKER-01-GLD", "Royal Gold & Emerald Beads", new BigDecimal("3499.00"), true, 20,
                                Map.of("plating", "22K Micron Gold", "gemstones", "Uncut Kundan & Emerald Beads"), "https://images.unsplash.com/photo-1599643478518-a784e5dc4c8f?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("JEWL-CHOKER-01-PRL", "Ivory Pearl & Ruby Beads", new BigDecimal("3699.00"), false, 15,
                                Map.of("plating", "22K Micron Gold", "gemstones", "Uncut Kundan & Ruby Beads"), "https://images.unsplash.com/photo-1515562141207-7a88fb7ce338?auto=format&fit=crop&w=800&q=80")
                )
        );

        createProductWithVariants(
                "JEWL-RING-02",
                "18K Gold Vermeil Solitaire Diamond Ring",
                "Handcrafted 18K yellow gold vermeil over sterling silver with a brilliant 2-carat conflict-free AAA clarity cubic zirconia solitaire stone.",
                new BigDecimal("1999.00"),
                "Jewellery",
                "https://images.unsplash.com/photo-1605100804763-247f67b3557e?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("JEWL-RING-02-US6", "Size 6 (16.5mm) / 18K Gold", new BigDecimal("1999.00"), true, 22,
                                Map.of("ringSize", "US 6 (16.5mm)", "metal", "18K Gold Vermeil", "gemstone", "2-Carat Solitaire"), "https://images.unsplash.com/photo-1605100804763-247f67b3557e?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("JEWL-RING-02-US7", "Size 7 (17.3mm) / 18K Gold", new BigDecimal("1999.00"), false, 25,
                                Map.of("ringSize", "US 7 (17.3mm)", "metal", "18K Gold Vermeil", "gemstone", "2-Carat Solitaire"), "https://images.unsplash.com/photo-1605100804763-247f67b3557e?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("JEWL-RING-02-US8", "Size 8 (18.1mm) / 18K Gold", new BigDecimal("1999.00"), false, 18,
                                Map.of("ringSize", "US 8 (18.1mm)", "metal", "18K Gold Vermeil", "gemstone", "2-Carat Solitaire"), "https://images.unsplash.com/photo-1605100804763-247f67b3557e?auto=format&fit=crop&w=800&q=80")
                )
        );

        createProductWithVariants(
                "JEWL-JHUMKA-03",
                "Antique Temple Gold Bell Jhumka Earrings",
                "Exquisite South Indian temple jewelry architecture featuring peacock cresting, dangling golden bell droplets, and vintage matte patina.",
                new BigDecimal("1499.00"),
                "Jewellery",
                "https://images.unsplash.com/photo-1535632066927-ab7c9ab60908?auto=format&fit=crop&w=800&q=80",
                defaultSellerId,
                List.of(
                        new VariantSeed("JEWL-JHUMKA-03-MED", "Medium Floral Bell (4.5cm)", new BigDecimal("1499.00"), true, 30,
                                Map.of("length", "4.5 cm", "finish", "Antique Matte Gold", "motif", "Peacock & Lotus"), "https://images.unsplash.com/photo-1535632066927-ab7c9ab60908?auto=format&fit=crop&w=800&q=80"),
                        new VariantSeed("JEWL-JHUMKA-03-LRG", "Grand Tiered Bell (6.0cm)", new BigDecimal("1899.00"), false, 16,
                                Map.of("length", "6.0 cm", "finish", "Antique Matte Gold", "motif", "Grand Temple Tier"), "https://images.unsplash.com/photo-1630019852942-f89202989a59?auto=format&fit=crop&w=800&q=80")
                )
        );
    }

    private void createProductWithVariants(String sku, String name, String desc, BigDecimal price, String cat, String img, Long defaultSellerId, List<VariantSeed> variants) {
        Product product = productRepository.findBySku(sku).orElseGet(() -> {
            Product p = Product.builder()
                    .sku(sku)
                    .name(name)
                    .description(desc)
                    .price(price)
                    .category(cat)
                    .imageUrl(img)
                    .sellerId(defaultSellerId)
                    .build();
            return productRepository.save(p);
        });

        if (product.getSellerId() == null && defaultSellerId != null) {
            product.setSellerId(defaultSellerId);
            product = productRepository.save(product);
        }

        if (product.getImageUrl() == null && img != null) {
            product.setImageUrl(img);
            product = productRepository.save(product);
        }

        for (VariantSeed vs : variants) {
            if (!productVariantRepository.existsBySku(vs.sku())) {
                ProductVariant variant = ProductVariant.builder()
                        .product(product)
                        .sku(vs.sku())
                        .title(vs.title())
                        .price(vs.price())
                        .imageUrl(vs.imageUrl())
                        .isDefault(vs.isDefault())
                        .active(true)
                        .attributes(new HashMap<>(vs.attributes()))
                        .build();
                ProductVariant savedVariant = productVariantRepository.save(variant);

                Inventory inventory = Inventory.builder()
                        .product(product)
                        .variant(savedVariant)
                        .availableStock(vs.stock())
                        .reservedStock(0)
                        .build();
                inventoryRepository.save(inventory);

                log.info("Seeded variant '{}' ({}) with stock {}", vs.title(), vs.sku(), vs.stock());
            }
        }
    }

    private record VariantSeed(String sku, String title, BigDecimal price, boolean isDefault, int stock, Map<String, String> attributes, String imageUrl) {}

    /**
     * Resilient, idempotent migration ensuring all existing database rows (Product, Inventory, CartItem, OrderItem)
     * are properly reconciled with ProductVariants without failing startup on partial runs or anomalies.
     */
    private void migrateExistingProductsToVariants() {
        List<Product> products = productRepository.findAll();
        for (Product rawProd : products) {
            try {
                Product prod = rawProd;
                // Ensure image is populated if missing
                if (prod.getImageUrl() == null && SEED_IMAGES.containsKey(prod.getSku())) {
                    prod.setImageUrl(SEED_IMAGES.get(prod.getSku()));
                    prod = productRepository.save(prod);
                }

                final Product currentProd = prod;

                // Ensure canonical default variant exists
                ProductVariant defaultVariant = productVariantRepository.findByProductIdAndIsDefaultTrue(currentProd.getId())
                        .orElseGet(() -> {
                            List<ProductVariant> existing = productVariantRepository.findByProductId(currentProd.getId());
                            if (!existing.isEmpty()) {
                                ProductVariant first = existing.get(0);
                                first.setDefault(true);
                                if (first.getImageUrl() == null) first.setImageUrl(currentProd.getImageUrl());
                                return productVariantRepository.save(first);
                            }
                            ProductVariant def = ProductVariant.builder()
                                    .product(currentProd)
                                    .sku(currentProd.getSku())
                                    .title("Standard Edition")
                                    .price(currentProd.getPrice())
                                    .imageUrl(currentProd.getImageUrl())
                                    .isDefault(true)
                                    .active(true)
                                    .attributes(new HashMap<>(Map.of("color", "Standard", "edition", "Standard")))
                                    .build();
                            return productVariantRepository.save(def);
                        });

                // Reconcile unlinked Inventory rows for this product
                List<Inventory> inventories = inventoryRepository.findAllByProductId(currentProd.getId());
                if (inventories.isEmpty()) {
                    Inventory inv = Inventory.builder()
                            .product(currentProd)
                            .variant(defaultVariant)
                            .availableStock(20)
                            .reservedStock(0)
                            .build();
                    inventoryRepository.save(inv);
                } else {
                    for (Inventory inv : inventories) {
                        if (inv.getVariant() == null) {
                            inv.setVariant(defaultVariant);
                            inventoryRepository.save(inv);
                            log.info("Reconciled unlinked inventory #{} to default variant #{} for '{}'",
                                    inv.getId(), defaultVariant.getId(), currentProd.getName());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Isolated migration note for product '{}' ({}): {}", rawProd.getName(), rawProd.getId(), e.getMessage());
            }
        }

        // Targeted, single-pass batch reconciliation for legacy unlinked CartItems (eliminates N+1 table scans)
        try {
            List<CartItem> unlinkedCartItems = cartItemRepository.findByVariantIsNull();
            if (!unlinkedCartItems.isEmpty()) {
                Map<Long, ProductVariant> defaultVariantMap = productVariantRepository.findByIsDefaultTrue().stream()
                        .collect(Collectors.toMap(v -> v.getProduct().getId(), v -> v, (a, b) -> a));
                int reconciledCount = 0;
                for (CartItem ci : unlinkedCartItems) {
                    if (ci.getProduct() != null && defaultVariantMap.containsKey(ci.getProduct().getId())) {
                        ci.setVariant(defaultVariantMap.get(ci.getProduct().getId()));
                        cartItemRepository.save(ci);
                        reconciledCount++;
                    }
                }
                log.info("Batch-reconciled {} legacy unlinked CartItem rows to default variants.", reconciledCount);
            }
        } catch (Exception e) {
            log.warn("Error during batch CartItem variant reconciliation: {}", e.getMessage());
        }

        // Targeted, single-pass batch reconciliation for legacy unlinked OrderItems (eliminates N+1 table scans)
        try {
            List<Order> unlinkedOrders = orderRepository.findOrdersWithUnlinkedItems();
            if (!unlinkedOrders.isEmpty()) {
                Map<Long, ProductVariant> defaultVariantMap = productVariantRepository.findByIsDefaultTrue().stream()
                        .collect(Collectors.toMap(v -> v.getProduct().getId(), v -> v, (a, b) -> a));
                int reconciledOrders = 0;
                for (Order order : unlinkedOrders) {
                    boolean orderModified = false;
                    if (order.getItems() != null) {
                        for (OrderItem oi : order.getItems()) {
                            if (oi.getProduct() != null && oi.getVariant() == null && defaultVariantMap.containsKey(oi.getProduct().getId())) {
                                ProductVariant def = defaultVariantMap.get(oi.getProduct().getId());
                                oi.setVariant(def);
                                if (oi.getSku() == null) oi.setSku(def.getSku());
                                if (oi.getVariantTitle() == null) oi.setVariantTitle(def.getTitle());
                                orderModified = true;
                            }
                        }
                    }
                    if (orderModified) {
                        orderRepository.save(order);
                        reconciledOrders++;
                    }
                }
                log.info("Batch-reconciled {} legacy Orders with unlinked OrderItems to default variants.", reconciledOrders);
            }
        } catch (Exception e) {
            log.warn("Error during batch OrderItem variant reconciliation: {}", e.getMessage());
        }
    }
}
