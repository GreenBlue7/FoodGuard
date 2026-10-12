package com.my.food_ad_check.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Replaces only lists that still exactly match the previous parser's output. */
@Component
@ConditionalOnProperty(name = "foodsafety.approved-functions-reconcile-enabled", havingValue = "true")
public class ApprovedFunctionsReconciliation implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApprovedFunctionsReconciliation.class);
    private static final int BATCH_SIZE = 500;
    private static final String SELECT_SOURCES = """
            SELECT prdlst_report_no, primary_fnclty
            FROM products
            WHERE prdlst_report_no > ?
              AND primary_fnclty IS NOT NULL
              AND btrim(primary_fnclty) <> ''
            ORDER BY prdlst_report_no
            LIMIT ?
            """;
    private static final String SELECT_FUNCTIONS = """
            SELECT prdlst_report_no, position, function_text
            FROM product_approved_functions
            WHERE prdlst_report_no IN (%s)
            ORDER BY prdlst_report_no, position
            FOR UPDATE
            """;
    private static final String DELETE_FUNCTIONS = """
            DELETE FROM product_approved_functions WHERE prdlst_report_no = ?
            """;
    private static final String INSERT_FUNCTION = """
            INSERT INTO product_approved_functions (prdlst_report_no, position, function_text)
            VALUES (?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ProductApiService productApiService;
    private final TransactionTemplate transactionTemplate;
    private final boolean apply;

    public ApprovedFunctionsReconciliation(JdbcTemplate jdbcTemplate,
                                           ProductApiService productApiService,
                                           PlatformTransactionManager transactionManager,
                                           @Value("${foodsafety.approved-functions-reconcile-mode:preview}") String mode) {
        if (!List.of("preview", "apply").contains(mode)) {
            throw new IllegalArgumentException("Invalid approved-functions reconcile mode");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.productApiService = productApiService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.apply = "apply".equals(mode);
    }

    @Override
    public void run(ApplicationArguments args) {
        String lastReportNo = "";
        Totals totals = new Totals();
        while (true) {
            List<ProductSource> batch = jdbcTemplate.query(SELECT_SOURCES,
                    (rs, rowNum) -> new ProductSource(rs.getString(1), rs.getString(2)),
                    lastReportNo, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            Totals batchTotals = transactionTemplate.execute(status -> reconcileBatch(batch));
            totals.add(batchTotals);
            lastReportNo = batch.get(batch.size() - 1).reportNo();
            if (totals.products % 5000 == 0) {
                log.info("Approved-functions reconciliation progress: mode={}, products={}",
                        apply ? "apply" : "preview", totals.products);
            }
        }
        log.info("Approved-functions reconciliation finished: mode={}, products={}, "
                        + "changedProducts={}, alreadyReconciledProducts={}, "
                        + "skippedModifiedProducts={}, skippedAmbiguousProducts={}, "
                        + "previousEntries={}, revisedEntries={}, sampleReportNos={}, "
                        + "ambiguousReportNos={}, modifiedReportNos={}",
                apply ? "apply" : "preview", totals.products, totals.changedProducts,
                totals.alreadyReconciledProducts, totals.skippedModifiedProducts,
                totals.skippedAmbiguousProducts, totals.previousEntries, totals.revisedEntries,
                totals.sampleReportNos, totals.ambiguousReportNos, totals.modifiedReportNos);
        log.info("Approved-functions removed-item candidates (top 30): {}",
                totals.removedCandidates.entrySet().stream()
                        .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                        .limit(30).toList());
    }

    private Totals reconcileBatch(List<ProductSource> batch) {
        String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
        Object[] reportNos = batch.stream().map(ProductSource::reportNo).toArray();
        Map<String, List<StoredFunction>> storedByProduct = new HashMap<>();
        jdbcTemplate.query(SELECT_FUNCTIONS.formatted(placeholders), rs -> {
            storedByProduct.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                    .add(new StoredFunction(rs.getInt(2), rs.getString(3)));
        }, reportNos);

        Totals totals = new Totals();
        List<Object[]> deleteRows = new ArrayList<>();
        List<Object[]> insertRows = new ArrayList<>();
        for (ProductSource source : batch) {
            totals.products++;
            List<String> previous = productApiService.parsePrimaryFncltyLegacy(source.primaryFnclty());
            List<String> revised = productApiService.parsePrimaryFnclty(source.primaryFnclty());
            if (previous.equals(revised)) {
                continue;
            }
            List<StoredFunction> stored = storedByProduct.getOrDefault(source.reportNo(), List.of());
            if (matchesPrevious(stored, revised)) {
                totals.alreadyReconciledProducts++;
                continue;
            }
            if (!matchesPrevious(stored, previous)) {
                totals.skippedModifiedProducts++;
                if (totals.modifiedReportNos.size() < 10) {
                    totals.modifiedReportNos.add(source.reportNo());
                }
                continue;
            }
            if (!isConservativeChange(previous, revised)) {
                totals.skippedAmbiguousProducts++;
                if (totals.ambiguousReportNos.size() < 10) {
                    totals.ambiguousReportNos.add(source.reportNo());
                }
                continue;
            }
            totals.changedProducts++;
            totals.previousEntries += previous.size();
            totals.revisedEntries += revised.size();
            for (String old : previous) {
                String withoutMarker = LEADING_ALPHA_MARKER.matcher(old).replaceFirst("");
                if (!revised.contains(old) && !revised.contains(withoutMarker)) {
                    totals.removedCandidates.merge(old, 1L, Long::sum);
                }
            }
            if (totals.sampleReportNos.size() < 10) {
                totals.sampleReportNos.add(source.reportNo());
            }
            if (apply) {
                deleteRows.add(new Object[] {source.reportNo()});
                for (int position = 0; position < revised.size(); position++) {
                    insertRows.add(new Object[] {source.reportNo(), position, revised.get(position)});
                }
            }
        }
        if (apply && !deleteRows.isEmpty()) {
            jdbcTemplate.batchUpdate(DELETE_FUNCTIONS, deleteRows);
            if (!insertRows.isEmpty()) {
                jdbcTemplate.batchUpdate(INSERT_FUNCTION, insertRows);
            }
        }
        return totals;
    }

    static boolean matchesPrevious(List<StoredFunction> stored, List<String> previous) {
        if (stored.size() != previous.size()) {
            return false;
        }
        for (int index = 0; index < previous.size(); index++) {
            StoredFunction function = stored.get(index);
            if (function.position() != index || !function.text().equals(previous.get(index))) {
                return false;
            }
        }
        return true;
    }

    static boolean isConservativeChange(List<String> previous, List<String> revised) {
        for (String current : revised) {
            if (previous.contains(current)) {
                continue;
            }
            boolean labelOnly = false;
            for (String old : previous) {
                if (LEADING_ALPHA_MARKER.matcher(old).replaceFirst("").equals(current)) {
                    labelOnly = true;
                    break;
                }
            }
            if (!labelOnly) {
                return false;
            }
        }
        return true;
    }

    private static final Pattern LEADING_ALPHA_MARKER = Pattern.compile("^\\([가-하]\\)\\s*");

    private record ProductSource(String reportNo, String primaryFnclty) {
    }

    record StoredFunction(int position, String text) {
    }

    private static final class Totals {
        private long products;
        private long changedProducts;
        private long alreadyReconciledProducts;
        private long skippedModifiedProducts;
        private long skippedAmbiguousProducts;
        private long previousEntries;
        private long revisedEntries;
        private final List<String> sampleReportNos = new ArrayList<>();
        private final List<String> ambiguousReportNos = new ArrayList<>();
        private final List<String> modifiedReportNos = new ArrayList<>();
        private final Map<String, Long> removedCandidates = new HashMap<>();

        private void add(Totals other) {
            products += other.products;
            changedProducts += other.changedProducts;
            alreadyReconciledProducts += other.alreadyReconciledProducts;
            skippedModifiedProducts += other.skippedModifiedProducts;
            skippedAmbiguousProducts += other.skippedAmbiguousProducts;
            previousEntries += other.previousEntries;
            revisedEntries += other.revisedEntries;
            other.removedCandidates.forEach((item, count) ->
                    removedCandidates.merge(item, count, Long::sum));
            for (String reportNo : other.sampleReportNos) {
                if (sampleReportNos.size() >= 10) {
                    break;
                }
                sampleReportNos.add(reportNo);
            }
            for (String reportNo : other.ambiguousReportNos) {
                if (ambiguousReportNos.size() >= 10) {
                    break;
                }
                ambiguousReportNos.add(reportNo);
            }
            for (String reportNo : other.modifiedReportNos) {
                if (modifiedReportNos.size() >= 10) {
                    break;
                }
                modifiedReportNos.add(reportNo);
            }
        }
    }
}
