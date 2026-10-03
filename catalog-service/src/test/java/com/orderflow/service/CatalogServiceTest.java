package com.orderflow.service;

import com.orderflow.dto.ProductRequest;
import com.orderflow.dto.ProductResponse;
import com.orderflow.dto.ProductVariantRequest;
import com.orderflow.dto.ProductVariantResponse;
import com.orderflow.exception.BadRequestException;
import com.orderflow.exception.ResourceNotFoundException;
import com.orderflow.model.Product;
import com.orderflow.model.ProductVariant;
import com.orderflow.repository.ProductRepository;
import com.orderflow.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private CatalogService catalogService;

    private Product sampleProduct;
    private ProductVariant sampleVariant;

    @BeforeEach
    void setUp() {
        sampleProduct = Product.builder()
                .id(1L)
                .sku("PROD-TEST-01")
                .name("Test Gaming Keyboard")
                .description("RGB Mechanical Keyboard")
                .price(new BigDecimal("4999.00"))
                .category("Peripherals")
                .imageUrl("https://example.com/kb.jpg")
                .sellerId(10L)
                .variants(new ArrayList<>())
                .build();

        sampleVariant = ProductVariant.builder()
                .id(101L)
                .product(sampleProduct)
                .sku("PROD-TEST-01-BRN")
                .title("Tactile Brown")
                .price(new BigDecimal("4999.00"))
                .isDefault(true)
                .active(true)
                .attributes(Map.of("switch", "Brown"))
                .build();

        sampleProduct.addVariant(sampleVariant);
    }

    @Nested
    @DisplayName("Catalog Query Tests")
    class QueryTests {

        @Test
        @DisplayName("getAllProducts returns paged mapped responses")
        void getAllProducts_success() {
            when(productRepository.findAll(any(PageRequest.class)))
                    .thenReturn(new PageImpl<>(List.of(sampleProduct)));

            Page<ProductResponse> page = catalogService.getAllProducts(PageRequest.of(0, 10));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).getSku()).isEqualTo("PROD-TEST-01");
            assertThat(page.getContent().get(0).getVariants()).hasSize(1);
        }

        @Test
        @DisplayName("getProductById returns product when found")
        void getProductById_found() {
            when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));

            ProductResponse response = catalogService.getProductById(1L);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getName()).isEqualTo("Test Gaming Keyboard");
        }

        @Test
        @DisplayName("getProductById throws ResourceNotFoundException when missing")
        void getProductById_notFound() {
            when(productRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> catalogService.getProductById(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("getProductBySku returns product when found")
        void getProductBySku_found() {
            when(productRepository.findBySku("PROD-TEST-01")).thenReturn(Optional.of(sampleProduct));

            ProductResponse response = catalogService.getProductBySku("PROD-TEST-01");

            assertThat(response.getSku()).isEqualTo("PROD-TEST-01");
        }

        @Test
        @DisplayName("getCategories returns distinct categories")
        void getCategories_success() {
            when(productRepository.findDistinctCategories()).thenReturn(List.of("Audio", "Displays", "Peripherals"));

            List<String> categories = catalogService.getCategories();

            assertThat(categories).containsExactly("Audio", "Displays", "Peripherals");
        }
    }

    @Nested
    @DisplayName("Product Mutation Tests")
    class MutationTests {

        @Test
        @DisplayName("createProduct saves product and emits Kafka event")
        void createProduct_success() {
            when(productRepository.existsBySku("PROD-NEW-01")).thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(i -> {
                Product p = i.getArgument(0);
                p.setId(2L);
                return p;
            });

            ProductRequest request = ProductRequest.builder()
                    .sku("PROD-NEW-01")
                    .name("New Monitor")
                    .price(new BigDecimal("19999.00"))
                    .category("Displays")
                    .sellerId(5L)
                    .variants(List.of(
                            ProductVariantRequest.builder()
                                    .sku("PROD-NEW-01-V1")
                                    .title("Standard")
                                    .price(new BigDecimal("19999.00"))
                                    .isDefault(true)
                                    .build()
                    ))
                    .build();

            when(productVariantRepository.existsBySku("PROD-NEW-01-V1")).thenReturn(false);

            ProductResponse created = catalogService.createProduct(request);

            assertThat(created).isNotNull();
            assertThat(created.getSku()).isEqualTo("PROD-NEW-01");
            verify(productRepository, atLeastOnce()).save(any(Product.class));
            verify(kafkaTemplate).send(eq("catalog-events"), eq("PROD-NEW-01"), any());
        }

        @Test
        @DisplayName("createProduct throws BadRequestException on duplicate SKU")
        void createProduct_duplicateSku() {
            when(productRepository.existsBySku("PROD-TEST-01")).thenReturn(true);

            ProductRequest request = ProductRequest.builder()
                    .sku("PROD-TEST-01")
                    .name("Duplicate")
                    .price(new BigDecimal("100.00"))
                    .build();

            assertThatThrownBy(() -> catalogService.createProduct(request))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("deleteProduct removes entity and publishes delete event")
        void deleteProduct_success() {
            when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));

            catalogService.deleteProduct(1L);

            verify(productRepository).delete(sampleProduct);
            verify(kafkaTemplate).send(eq("catalog-events"), eq("PROD-TEST-01"), any());
        }
    }
}
