package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.repository.ProductRepository;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class ProductApiService {

    private static final Logger log = LoggerFactory.getLogger(ProductApiService.class);
    private static final Pattern NUMBERED_CLAUSE = Pattern.compile("(?=[①-⑳])|(?=\\(?\\d{1,2}[.)]\\s)");
    private static final Pattern CLAIM_SEPARATOR = Pattern.compile("\\s*[·ㆍ・，;；/\\n]+\\s*");
    private static final Pattern COMMA_CLAIM_BOUNDARY = Pattern.compile(
            "(?<=필요)\\s*,\\s*|(?<=있음)\\s*,\\s*|(?<=줌)\\s*,\\s*|(?<=관여)\\s*,\\s*");
    private static final Pattern APPROVED_CLAIM_SUFFIX = Pattern.compile(
            "\\s*(?:에\\s*)?도움을\\s*줄\\s*수\\s*있(?:음|습니다)\\s*[.!。]?\\s*$");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(?:[①-⑳]|\\(?\\d{1,2}[.)])\\s*");

    private final ProductRepository productRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String apiBaseUrl;
    private final AtomicBoolean importRunning = new AtomicBoolean();

    @Autowired
    public ProductApiService(ProductRepository productRepository,
                             @Value("${foodsafety.api-key:}") String apiKey,
                             @Value("${foodsafety.api-base-url:http://openapi.foodsafetykorea.go.kr/api}") String apiBaseUrl,
                             @Value("${foodsafety.timeout-ms:30000}") int timeoutMs) {
        this(productRepository, timedRestTemplate(timeoutMs), apiKey, apiBaseUrl);
    }

    ProductApiService(ProductRepository productRepository, RestTemplate restTemplate,
                      String apiKey, String apiBaseUrl) {
        this.productRepository = productRepository;
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
    }

    private static RestTemplate timedRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new RestTemplate(factory);
    }

    public String loadAllProductData() {
        if (!importRunning.compareAndSet(false, true)) {
            return "데이터 적재 진행 중: 이전 요청이 끝난 뒤 다시 시도해 주세요.";
        }
        try {
            return doLoadAllProductData();
        } finally {
            importRunning.set(false);
        }
    }

    private String doLoadAllProductData() {
        if (apiKey == null || apiKey.isBlank()) {
            return "데이터 적재 실패: FOOD_SAFETY_API_KEY 설정이 필요합니다.";
        }

        int chunkSize = 1000;
        int startIdx = 1;
        int totalSaved = 0;
        int totalSkipped = 0;

        while (true) {
            int endIdx = startIdx + chunkSize - 1;
            var uri = UriComponentsBuilder.fromUriString(apiBaseUrl)
                    .pathSegment(apiKey, "I0030", "json", String.valueOf(startIdx), String.valueOf(endIdx))
                    .build()
                    .toUri();

            try {
                String response = restTemplate.getForObject(uri, String.class);
                if (response == null || response.isBlank()) {
                    return loadFailure("식약처 API에서 빈 응답을 받았습니다.", totalSaved, totalSkipped);
                }
                JsonNode root = objectMapper.readTree(response);
                JsonNode data = root.path("I0030");
                if (!data.isObject()) {
                    return loadFailure("식약처 API 응답에 I0030 데이터가 없습니다.", totalSaved, totalSkipped);
                }
                JsonNode result = data.path("RESULT");
                if (result.isObject() && result.hasNonNull("CODE")
                        && !"INFO-000".equals(result.path("CODE").asText())) {
                    return loadFailure("식약처 API 오류: " + result.path("MSG").asText("원인을 확인할 수 없습니다."),
                            totalSaved, totalSkipped);
                }

                int totalCount = data.path("total_count").asInt(-1);
                if (totalCount < 0) {
                    return loadFailure("식약처 API 응답에 유효한 total_count가 없습니다.", totalSaved, totalSkipped);
                }

                JsonNode rows = data.path("row");
                if (!rows.isArray()) {
                    if (totalCount == 0) {
                        break;
                    }
                    return loadFailure("식약처 API 응답의 row 형식이 올바르지 않습니다.", totalSaved, totalSkipped);
                }

                if (rows.isEmpty()) {
                    if (totalCount >= startIdx) {
                        return loadFailure("요청 범위에 데이터가 있지만 row가 비어 있습니다.", totalSaved, totalSkipped);
                    }
                    break;
                }
                if (rows.size() < chunkSize && endIdx < totalCount) {
                    return loadFailure("요청한 페이지에 전체 데이터가 들어오지 않았습니다.", totalSaved, totalSkipped);
                }

                List<Product> productList = new ArrayList<>();
                for (JsonNode row : rows) {
                    String reportNo = row.path("PRDLST_REPORT_NO").asText("").trim();
                    String productName = row.path("PRDLST_NM").asText("").trim();
                    if (reportNo.isEmpty() || productName.isEmpty()) {
                        totalSkipped++;
                        continue;
                    }

                    Product product = new Product();
                    product.setPrdlstReportNo(reportNo);
                    product.setPrdlstNm(productName);
                    product.setBsshNm(row.path("BSSH_NM").asText());
                    product.setPrimaryFnclty(row.path("PRIMARY_FNCLTY").asText());
                    product.setProductType("건강기능식품");
                    product.setApprovedFunctions(parsePrimaryFnclty(product.getPrimaryFnclty()));

                    productList.add(product);
                }

                if (!productList.isEmpty()) {
                    productRepository.saveAll(productList);
                }
                totalSaved += productList.size();
                log.info("I0030 적재 진행: 저장 {}건, 필수값 누락으로 제외 {}건", totalSaved, totalSkipped);

                if (endIdx >= totalCount) {
                    break;
                }
                startIdx = endIdx + 1;

            } catch (Exception e) {
                log.error("I0030 적재 실패 (시작 인덱스 {}, 원인 {})", startIdx,
                        e.getClass().getSimpleName());
                return loadFailure("식약처 API 호출 또는 DB 저장 중 오류가 발생했습니다.", totalSaved, totalSkipped);
            }
        }
        return "I0030 적재 완료: 저장 " + totalSaved + "건, 필수값 누락으로 제외 " + totalSkipped + "건";
    }

    private String loadFailure(String reason, int totalSaved, int totalSkipped) {
        return "데이터 적재 실패: " + reason + " (현재까지 저장 " + totalSaved
                + "건, 제외 " + totalSkipped + "건)";
    }

    public List<String> parsePrimaryFnclty(String primaryFnclty) {
        if (primaryFnclty == null || primaryFnclty.trim().isEmpty()) {
            return List.of();
        }

        String normalized = Normalizer.normalize(primaryFnclty, Normalizer.Form.NFC)
                .replace('\u00a0', ' ')
                .replace('\r', '\n');
        Map<String, String> claims = new LinkedHashMap<>();

        for (String line : normalized.split("\\n")) {
            String cleanedLine = line.trim();
            if (cleanedLine.isEmpty()) {
                continue;
            }

            boolean labeledLine = cleanedLine.matches("^[-•*].*");
            cleanedLine = cleanedLine.replaceFirst("^[-•*]\\s*", "");
            cleanedLine = cleanedLine.replaceFirst("^\\[[^]]+\\]\\s*", "");
            if (labeledLine) {
                cleanedLine = cleanedLine.replaceFirst("^[^:：]{1,40}[:：]\\s*", "");
            }

            for (String numberedClause : NUMBERED_CLAUSE.split(cleanedLine)) {
                String clause = LEADING_NUMBER.matcher(numberedClause).replaceFirst("");
                for (String fragment : CLAIM_SEPARATOR.split(clause)) {
                    for (String commaSeparated : COMMA_CLAIM_BOUNDARY.split(fragment)) {
                        String claim = normalizeClaim(commaSeparated);
                        if (!claim.isEmpty()) {
                            claims.putIfAbsent(claim.toLowerCase(Locale.ROOT), claim);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(claims.values());
    }

    private String normalizeClaim(String fragment) {
        String claim = APPROVED_CLAIM_SUFFIX.matcher(fragment).replaceFirst("")
                .replaceAll("^[\\s:：,，;；]+|[\\s:：,，;；.!。]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return claim;
    }
}
