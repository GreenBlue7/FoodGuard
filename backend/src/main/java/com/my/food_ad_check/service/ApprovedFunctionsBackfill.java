package com.my.food_ad_check.service;

import java.util.ArrayList;
import java.util.List;
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

/** One-time, opt-in migration for products imported before approved functions were stored. */
@Component
@ConditionalOnProperty(name = "foodsafety.approved-functions-backfill-enabled", havingValue = "true")
public class ApprovedFunctionsBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApprovedFunctionsBackfill.class);
    private static final int BATCH_SIZE = 500;
    private static final String SELECT_PRODUCTS = """
            SELECT p.prdlst_report_no, p.primary_fnclty
            FROM products p
            WHERE p.prdlst_report_no > ?
              AND p.primary_fnclty IS NOT NULL
              AND btrim(p.primary_fnclty) <> ''
              %s
            ORDER BY p.prdlst_report_no
            LIMIT ?
            """;
    private static final String MISSING_FUNCTIONS = """
            AND NOT EXISTS (
                SELECT 1 FROM product_approved_functions f
                WHERE f.prdlst_report_no = p.prdlst_report_no
            )
            """;
    private static final String INSERT_FUNCTION = """
            INSERT INTO product_approved_functions (prdlst_report_no, position, function_text)
            VALUES (?, ?, ?)
            ON CONFLICT (prdlst_report_no, position) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ProductApiService productApiService;
    private final TransactionTemplate transactionTemplate;
    private final String mode;
    private final boolean apply;
    private final boolean refresh;

    public ApprovedFunctionsBackfill(JdbcTemplate jdbcTemplate, ProductApiService productApiService,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${foodsafety.approved-functions-backfill-mode:preview}") String mode) {
        if (!List.of("preview", "apply", "refresh-preview").contains(mode)) {
            throw new IllegalArgumentException("Invalid approved-functions backfill mode");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.productApiService = productApiService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.mode = mode;
        this.apply = "apply".equals(mode);
        this.refresh = mode.startsWith("refresh-");
    }

    @Override
    public void run(ApplicationArguments args) {
        String lastReportNo = "";
        long products = 0;
        long functions = 0;
        long withoutFunctions = 0;
        long overThirtyFunctions = 0;
        int maxFunctionsPerProduct = 0;
        List<String> outlierReportNos = new ArrayList<>();
        String selectSql = SELECT_PRODUCTS.formatted(refresh ? "" : MISSING_FUNCTIONS);

        while (true) {
            List<ProductSource> batch = jdbcTemplate.query(selectSql,
                    (rs, rowNum) -> new ProductSource(rs.getString(1), rs.getString(2)),
                    lastReportNo, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }

            List<Object[]> insertRows = new ArrayList<>();
            for (ProductSource source : batch) {
                List<String> parsed = productApiService.parsePrimaryFnclty(source.primaryFnclty());
                if (parsed.isEmpty()) {
                    withoutFunctions++;
                }
                if (parsed.size() > 30) {
                    overThirtyFunctions++;
                    if (outlierReportNos.size() < 10) {
                        outlierReportNos.add(source.reportNo());
                    }
                }
                maxFunctionsPerProduct = Math.max(maxFunctionsPerProduct, parsed.size());
                for (int position = 0; position < parsed.size(); position++) {
                    insertRows.add(new Object[] {source.reportNo(), position, parsed.get(position)});
                }
            }

            if (apply && !insertRows.isEmpty()) {
                transactionTemplate.executeWithoutResult(status ->
                        jdbcTemplate.batchUpdate(INSERT_FUNCTION, insertRows));
            }
            products += batch.size();
            functions += insertRows.size();
            lastReportNo = batch.get(batch.size() - 1).reportNo();
            if (products % 5000 == 0) {
                log.info("Approved-functions backfill progress: mode={}, products={}", mode(), products);
            }
        }

        log.info("Approved-functions backfill finished: mode={}, products={}, functions={}, "
                        + "productsWithoutFunctions={}, maxFunctionsPerProduct={}, "
                        + "productsOverThirtyFunctions={}, sampleReportNos={}",
                mode(), products, functions, withoutFunctions, maxFunctionsPerProduct,
                overThirtyFunctions, outlierReportNos);
    }

    private String mode() {
        return mode;
    }

    private record ProductSource(String reportNo, String primaryFnclty) {
    }
}
