package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.entity.ProductFunctionChange;
import com.my.food_ad_check.repository.ProductRepository;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final Pattern NUMBERED_CLAUSE = Pattern.compile(
            "(?=[①-⑳])|(?=\\(\\d{1,2}[.)]\\s)|(?<!\\()(?=\\d{1,2}[.)]\\s)");
    private static final Pattern NUMBERED_PREFIX = Pattern.compile(
            "^(?:[①-⑳]|\\(\\d{1,2}[.)]|\\d{1,2}[.)])");
    private static final Pattern LISTED_CLAIM = Pattern.compile(
            "^\\s*(?:[-•*]\\s*|[①-⑳]|\\(\\d{1,2}[.)]|\\d{1,2}[.)]|\\([가-하]\\))");
    private static final Pattern CLEAR_SECTION_HEADING = Pattern.compile(
            "^(?:비타민\\s*[A-EK](?:\\s*\\d{1,2})?|프로바이오틱스|판토텐산|나이아신|비오틴|"
                    + "마그네슘|칼슘|아연|철|셀레늄|셀렌|요오드|망간|몰리브덴|구리|크롬|엽산|일일섭취량"
                    + "|셀레늄\\(or 셀렌\\)|셀레늄\\(또는 셀렌\\))$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_ALPHA_MARKER = Pattern.compile("^\\([가-하]\\)\\s*");
    private static final Pattern CLAIM_SEPARATOR = Pattern.compile("\\s*[，;；]+\\s*");
    private static final Pattern INTERPUNCT_SEPARATOR = Pattern.compile("\\s*[·ㆍ・･]+\\s*");
    private static final Pattern COMMA_CLAIM_BOUNDARY = Pattern.compile(
            "(?<=필요)\\s*,\\s*|(?<=있음)\\s*,\\s*|(?<=줌)\\s*,\\s*|(?<=관여)\\s*,\\s*");
    private static final Pattern APPROVED_CLAIM_SUFFIX = Pattern.compile(
            "\\s*(?:에\\s*)?도움을\\s*줄\\s*수\\s*있(?:음|습니다)\\s*[.!。]?\\s*$");
    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "^(?:[①-⑳]|\\(\\d{1,2}[.)]|\\d{1,2}[.)])\\s*");

    private final ProductRepository productRepository;
    private final ProductImportWriter importWriter;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String apiBaseUrl;
    private final AtomicBoolean importRunning = new AtomicBoolean();

    @Autowired
    public ProductApiService(ProductRepository productRepository, ProductImportWriter importWriter,
                             @Value("${foodsafety.api-key:}") String apiKey,
                             @Value("${foodsafety.api-base-url:http://openapi.foodsafetykorea.go.kr/api}") String apiBaseUrl,
                             @Value("${foodsafety.timeout-ms:30000}") int timeoutMs) {
        this(productRepository, importWriter, timedRestTemplate(timeoutMs), apiKey, apiBaseUrl);
    }

    ProductApiService(ProductRepository productRepository, ProductImportWriter importWriter, RestTemplate restTemplate,
                      String apiKey, String apiBaseUrl) {
        this.productRepository = productRepository;
        this.importWriter = importWriter;
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
        return importSince(null).message();
    }

    public record ImportOutcome(boolean completed, String message) { }
    private static final class ImportProgress { boolean completed; int skipped; }

    /** Null means a full import. A date adds the documented I0030 CHNG_DT path parameter. */
    public ImportOutcome importSince(LocalDate changedSince) {
        if (!importRunning.compareAndSet(false, true)) {
            return new ImportOutcome(false, "데이터 적재 진행 중: 이전 요청이 끝난 뒤 다시 시도해 주세요.");
        }
        try {
            ImportProgress progress = new ImportProgress();
            String message = doLoadProductData(changedSince, progress);
            return new ImportOutcome(progress.completed && progress.skipped == 0, message);
        } finally {
            importRunning.set(false);
        }
    }

    private String doLoadProductData(LocalDate changedSince, ImportProgress progress) {
        if (apiKey == null || apiKey.isBlank()) {
            return "데이터 적재 실패: FOOD_SAFETY_API_KEY 설정이 필요합니다.";
        }

        int chunkSize = 1000;
        int startIdx = 1;
        int totalSaved = 0;
        int totalSkipped = 0;
        int changedSourceCount = 0;
        int unchangedCount = 0;

        while (true) {
            int endIdx = startIdx + chunkSize - 1;
            var builder = UriComponentsBuilder.fromUriString(apiBaseUrl)
                    .pathSegment(apiKey, "I0030", "json", String.valueOf(startIdx), String.valueOf(endIdx));
            if (changedSince != null) {
                builder.pathSegment("CHNG_DT=" + changedSince.format(DateTimeFormatter.BASIC_ISO_DATE));
            }
            var uri = builder.build().toUri();

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
                // The API may omit total_count/row when a valid incremental request has no changes.
                if (changedSince != null && startIdx == 1 && "INFO-200".equals(result.path("CODE").asText())) {
                    break;
                }
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
                List<ProductFunctionChange> sourceChanges = new ArrayList<>();
                int pageChangedSourceCount = 0;
                for (JsonNode row : rows) {
                    String reportNo = row.path("PRDLST_REPORT_NO").asText("").trim();
                    String productName = row.path("PRDLST_NM").asText("").trim();
                    if (reportNo.isEmpty() || productName.isEmpty() || !row.path("PRIMARY_FNCLTY").isTextual()) {
                        totalSkipped++;
                        progress.skipped++;
                        continue;
                    }

                    String newPrimaryFnclty = row.path("PRIMARY_FNCLTY").asText();
                    Product product = productRepository.findById(reportNo).orElse(null);
                    boolean detailsChanged = product != null && JudgeProductDetails.update(product, row);
                    if (product != null && Objects.equals(product.getPrimaryFnclty(), newPrimaryFnclty)
                            && Objects.equals(product.getPrdlstNm(), productName)
                            && Objects.equals(product.getBsshNm(), row.path("BSSH_NM").asText())
                            && "건강기능식품".equals(product.getProductType())
                            && !product.isFunctionSourceReviewRequired() && !detailsChanged) {
                        unchangedCount++;
                        continue;
                    }
                    if (product == null) {
                        product = new Product();
                        product.setPrdlstReportNo(reportNo);
                        refreshFunctions(product, newPrimaryFnclty);
                    } else if (!Objects.equals(product.getPrimaryFnclty(), newPrimaryFnclty)) {
                        pageChangedSourceCount++;
                        sourceChanges.add(new ProductFunctionChange(reportNo, product.getPrimaryFnclty(),
                                newPrimaryFnclty, LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))));
                        refreshFunctions(product, newPrimaryFnclty);
                    } else if (product.isFunctionSourceReviewRequired()) {
                        // Retry extraction from current source; never restore the older list.
                        List<String> before = List.copyOf(product.getApprovedFunctions());
                        refreshFunctions(product, newPrimaryFnclty);
                        if (product.isFunctionSourceReviewRequired() && before.equals(product.getApprovedFunctions())
                                && Objects.equals(product.getPrdlstNm(), productName)
                                && Objects.equals(product.getBsshNm(), row.path("BSSH_NM").asText())
                                && "건강기능식품".equals(product.getProductType()) && !detailsChanged) {
                            unchangedCount++;
                            continue;
                        }
                    }
                    JudgeProductDetails.update(product, row);
                    product.setPrdlstNm(productName);
                    product.setBsshNm(row.path("BSSH_NM").asText());
                    product.setPrimaryFnclty(newPrimaryFnclty);
                    product.setProductType("건강기능식품");

                    productList.add(product);
                }

                if (!productList.isEmpty()) {
                    importWriter.savePage(productList, sourceChanges);
                }
                totalSaved += productList.size();
                changedSourceCount += pageChangedSourceCount;
                log.info("I0030 적재 진행: 저장 {}건, 필수값 누락으로 제외 {}건", totalSaved, totalSkipped);
                if (pageChangedSourceCount > 0) {
                    log.info("I0030 원문 변경 {}건: 최신 원문에서 기능성 목록을 다시 추출했습니다.",
                            pageChangedSourceCount);
                }

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
        progress.completed = true;
        return "I0030 적재 완료: 저장 " + totalSaved + "건, 필수값 누락으로 제외 " + totalSkipped
                + "건, 기능성 원문 변경(목록 자동 갱신) " + changedSourceCount + "건"
                + ", 동일하여 저장 생략 " + unchangedCount + "건"
                + (totalSkipped == 0 ? "" : ", 누락 자료가 있어 정기 갱신 기준일은 유지됩니다.");
    }

    private String loadFailure(String reason, int totalSaved, int totalSkipped) {
        return "데이터 적재 실패: " + reason + " (현재까지 저장 " + totalSaved
                + "건, 제외 " + totalSkipped + "건)";
    }

    public List<String> parsePrimaryFnclty(String primaryFnclty) {
        var prepared = FunctionSourcePreprocessor.prepare(primaryFnclty);
        return prepared.restore(parsePrimaryFnclty(prepared.text(), true));
    }

    private void refreshFunctions(Product product, String source) {
        List<String> functions = parsePrimaryFnclty(source);
        boolean extractable = source != null && !source.isBlank() && !functions.isEmpty()
                && functions.size() <= 100
                && functions.stream().allMatch(f -> f.length() <= 4000 && f.matches("(?s).*[가-힣].*")
                        && !CLEAR_SECTION_HEADING.matcher(f).matches()
                        && !f.matches("(?i)^(?:해당\\s*없음|없음|미해당|기능성\\s*없음|기능성\\s*내용|주된\\s*기능성|N/A|null)$")
                        && !f.matches("(?s).*(※|각주|제조방법|제조 방법|참고사항).*"));
        // On failure, clear the stale list rather than interpreting it as current product information.
        product.setApprovedFunctions(extractable ? new ArrayList<>(functions) : new ArrayList<>());
        product.setFunctionSourceReviewRequired(!extractable);
    }

    // Kept for the one-time reconciliation: only unchanged v1 output may be replaced.
    List<String> parsePrimaryFncltyLegacy(String primaryFnclty) {
        return parsePrimaryFnclty(primaryFnclty, false);
    }

    private List<String> parsePrimaryFnclty(String primaryFnclty, boolean structuralCleanup) {
        if (primaryFnclty == null || primaryFnclty.trim().isEmpty()) {
            return List.of();
        }

        String normalized = Normalizer.normalize(primaryFnclty, Normalizer.Form.NFC)
                .replace('\u00a0', ' ')
                .replace('\r', '\n');
        Map<String, String> claims = new LinkedHashMap<>();
        String[] lines = normalized.split("\\n");

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String cleanedLine = lines[lineIndex].trim();
            if (cleanedLine.isEmpty()) {
                continue;
            }

            cleanedLine = cleanedLine.replaceFirst("^[-•*]\\s*", "");
            cleanedLine = cleanedLine.replaceFirst("^\\[[^]]+\\]\\s*", "");
            cleanedLine = cleanedLine.replaceFirst("^[^:：]{1,40}[:：]\\s*", "");
            if (structuralCleanup && isHeadingBeforeListedClaim(lines, lineIndex, cleanedLine)) {
                continue;
            }
            if (structuralCleanup) {
                cleanedLine = LEADING_ALPHA_MARKER.matcher(cleanedLine).replaceFirst("");
            }

            String[] numberedClauses = NUMBERED_CLAUSE.split(cleanedLine);
            for (int clauseIndex = 0; clauseIndex < numberedClauses.length; clauseIndex++) {
                String numberedClause = numberedClauses[clauseIndex];
                if (clauseIndex == 0 && numberedClauses.length > 1
                        && !NUMBERED_PREFIX.matcher(cleanedLine).find()
                        && !hasClaimEnding(numberedClause)) {
                    continue;
                }
                String clause = LEADING_NUMBER.matcher(numberedClause).replaceFirst("");
                for (String fragment : CLAIM_SEPARATOR.split(clause)) {
                    for (String commaSeparated : COMMA_CLAIM_BOUNDARY.split(fragment)) {
                        String[] interpunctParts = INTERPUNCT_SEPARATOR.split(commaSeparated);
                        boolean separateParts = interpunctParts.length > 1;
                        for (String part : interpunctParts) {
                            if (part.trim().length() < 3) {
                                separateParts = false;
                                break;
                            }
                        }
                        for (String part : separateParts ? interpunctParts : new String[] {commaSeparated}) {
                            String claim = normalizeClaim(part);
                            if (!claim.isEmpty()) {
                                claims.putIfAbsent(claim.toLowerCase(Locale.ROOT), claim);
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(claims.values());
    }

    private static boolean isHeadingBeforeListedClaim(String[] lines, int index, String current) {
        if (hasClaimEnding(current) || !CLEAR_SECTION_HEADING.matcher(current).matches()) {
            return false;
        }
        for (int next = index + 1; next < lines.length; next++) {
            String following = lines[next].trim();
            if (!following.isEmpty()) {
                return LISTED_CLAIM.matcher(following).find() && hasClaimEnding(following);
            }
        }
        return false;
    }

    private String normalizeClaim(String fragment) {
        String claim = APPROVED_CLAIM_SUFFIX.matcher(fragment).replaceFirst("")
                .replaceAll("^[\\s:：,，;；]+|[\\s:：,，;；.!。]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (claim.matches("^\\([^)]{1,10}\\)$") && !hasClaimEnding(fragment)) {
            return "";
        }
        return claim.length() >= 3 || (claim.length() >= 2 && hasClaimEnding(fragment))
                ? claim : "";
    }

    private static boolean hasClaimEnding(String text) {
        return text.contains("필요") || text.contains("도움") || text.contains("관여")
                || text.contains("개선") || text.contains("유지");
    }
}
