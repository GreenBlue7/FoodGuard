package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.entity.Product;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Lossless source storage and conservative list conversion, without an AI call. */
public final class JudgeProductDetails {
    private JudgeProductDetails() { }

    public static boolean update(Product product, JsonNode row) {
        boolean changed = false;
        changed |= update(row, "RAWMTRL_NM", product.getIngredientsSource(), product::setIngredientsSource);
        changed |= update(row, "IFTKN_ATNT_MATR_CN", product.getCautionsSource(), product::setCautionsSource);
        changed |= update(row, "NTK_MTHD", product.getIntake(), product::setIntake);
        changed |= update(row, "INDIV_RAWMTRL_NM", product.getIndividualIngredientsSource(), product::setIndividualIngredientsSource);
        changed |= update(row, "ETC_RAWMTRL_NM", product.getEtcIngredientsSource(), product::setEtcIngredientsSource);
        changed |= update(row, "CAP_RAWMTRL_NM", product.getCapsuleIngredientsSource(), product::setCapsuleIngredientsSource);
        changed |= update(row, "STDR_STND", product.getStandards(), product::setStandards);
        return changed;
    }

    private static boolean update(JsonNode row, String key, String previous, Consumer<String> setter) {
        // Partial responses must not erase a previously collected value.
        if (!row.has(key) || (!row.path(key).isTextual() && !row.path(key).isNull())) return false;
        String value = row.path(key).isNull() ? null : row.path(key).textValue();
        if (Objects.equals(previous, value)) return false;
        setter.accept(value);
        return true;
    }

    public static FastApiRequest.Product request(Product product, String type, List<String> functions) {
        if (product == null) return new FastApiRequest.Product(type, List.of(), functions);
        LinkedHashSet<String> other = new LinkedHashSet<>();
        other.addAll(ingredients(product.getIndividualIngredientsSource()));
        other.addAll(ingredients(product.getEtcIngredientsSource()));
        other.addAll(ingredients(product.getCapsuleIngredientsSource()));
        return new FastApiRequest.Product(type, List.of(product.getPrdlstNm()), List.copyOf(functions),
                ingredients(product.getIngredientsSource()), text(product.getPrimaryFnclty()),
                cautions(product.getCautionsSource()), text(product.getIntake()), List.copyOf(other), text(product.getStandards()));
    }

    private static String text(String value) { return value == null ? "" : value; }

    static List<String> ingredients(String source) {
        if (source == null || source.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder item = new StringBuilder();
        for (int offset = 0; offset < source.length(); offset++) {
            char ch = source.charAt(offset);
            if (ch == '(' || ch == '[' || ch == '{' || ch == '（') depth++;
            if (ch == ')' || ch == ']' || ch == '}' || ch == '）') depth = Math.max(0, depth - 1);
            if (depth == 0 && (ch == ',' || ch == '，' || ch == ';' || ch == '\n' || ch == '\r')) {
                add(result, item.toString());
                item.setLength(0);
            } else item.append(ch);
        }
        add(result, item.toString());
        return List.copyOf(new LinkedHashSet<>(result));
    }

    static List<String> cautions(String source) {
        if (source == null || source.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        // Do not split commas/number markers: they can be part of a single safety condition.
        for (String line : source.split("\\R")) add(result, line);
        return List.copyOf(result);
    }

    private static void add(List<String> target, String value) {
        if (!value.isBlank()) target.add(value.strip());
    }
}
