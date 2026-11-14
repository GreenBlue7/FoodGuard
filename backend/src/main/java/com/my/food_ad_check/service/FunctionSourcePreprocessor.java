package com.my.food_ad_check.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Separates source layout from claim text without deleting manufacturing conditions. */
final class FunctionSourcePreprocessor {
    private static final Pattern LANGUAGE = Pattern.compile(
            "(?i)^[\\[(]?\\s*(국문|한글|영문|영어|korean|english)\\s*[\\])]?[\\s:：]*$");
    private static final Pattern LANGUAGE_LABEL = Pattern.compile("(?i)[\\[(]\\s*(국문|한글|영문|영어|korean|english)\\s*[\\])]");
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^(?:[①-⑳]|\\(\\d{1,2}[.)]|\\d{1,2}[.)]|\\([가-하]\\))\\s*");
    private static final Pattern INGREDIENT = Pattern.compile(
            "^(?:비타민\\s*[A-EK](?:\\s*\\d{1,2})?|프로바이오틱스|판토텐산|나이아신|비오틴|"
                    + "마그네슘|칼슘|아연|철|셀레늄|셀렌|요오드|망간|몰리브덴|구리|크롬|엽산|테아닌|"
                    + "셀레늄\\(or 셀렌\\)|셀레늄\\(또는 셀렌\\))$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INGREDIENT_NAME = Pattern.compile(
            "^[가-힣A-Za-z0-9 (),.·ㆍ‧-]{1,100}(?:추출물|추출분말|추출액|분말|오일|홍삼|유산균|콜라겐|식이섬유)"
                    + "(?:\\([^)]*(?:인정|원료)[^)]*\\))?$");
    private static final Pattern CONDITION = Pattern.compile("\\[[^\\[\\]]*(?:제조|한함|경우|조건)[^\\[\\]]*\\]");

    record Prepared(String text, Map<String, String> conditions) {
        List<String> restore(List<String> candidates) {
            return candidates.stream().map(candidate -> {
                String result = candidate;
                for (var entry : conditions.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
                return result;
            }).distinct().toList();
        }
    }

    static Prepared prepare(String source) {
        if (source == null) return new Prepared(null, Map.of());
        String normalized = Normalizer.normalize(source, Normalizer.Form.NFC)
                .replace('\u00a0', ' ').replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n");
        boolean hasKoreanContent = java.util.Arrays.stream(lines)
                .anyMatch(line -> hasKorean(LANGUAGE_LABEL.matcher(line).replaceAll(""))
                        && !LANGUAGE.matcher(line.trim()).matches());
        List<String> kept = new ArrayList<>();
        boolean englishSection = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            var language = LANGUAGE.matcher(line);
            if (language.matches()) {
                englishSection = language.group(1).matches("(?i)영문|영어|english");
                continue;
            }
            if (line.equalsIgnoreCase("null")) continue;
            line = removeBracketHeading(line);
            if (line.isEmpty()) continue;
            boolean englishTail = false;
            var labels = LANGUAGE_LABEL.matcher(line);
            while (labels.find()) {
                boolean english = labels.group(1).matches("(?i)영문|영어|english");
                if (english) {
                    String prefix = line.substring(0, labels.start()).trim();
                    String suffix = line.substring(labels.end()).trim();
                    englishSection = true;
                    if (hasKoreanContent && hasKorean(prefix)) {
                        line = prefix;
                        englishTail = true;
                    } else {
                        line = prefix + suffix;
                    }
                    break;
                }
            }
            line = LANGUAGE_LABEL.matcher(line).replaceAll("").trim();
            if (hasKoreanContent && !hasKorean(line) && (englishSection || looksLikeTranslation(line))) continue;
            // An unlabelled Korean claim can follow the end of an English translation block.
            if (hasKorean(line) && !englishTail) englishSection = false;
            String title = NUMBER_PREFIX.matcher(stripBullet(line)).replaceFirst("").replaceAll("[:：]$", "").trim();
            if (title.matches("^(?:주된\\s*기능성|기능성\\s*(?:내용|정보)?)$")) continue;
            if (isIngredientTitle(title) && followedByKoreanText(lines, i)) continue;
            // A colon on a separate numbered-claim line belongs to the preceding heading.
            line = line.replaceFirst("^[:：]\\s*", "");
            kept.add(line);
        }

        String text = String.join("\n", kept);
        var matcher = CONDITION.matcher(text);
        StringBuffer protectedText = new StringBuffer();
        Map<String, String> conditions = new LinkedHashMap<>();
        while (matcher.find()) {
            String token = "\ue000CONDITION" + conditions.size() + "\ue001";
            conditions.put(token, matcher.group());
            matcher.appendReplacement(protectedText, java.util.regex.Matcher.quoteReplacement(token));
        }
        matcher.appendTail(protectedText);
        return new Prepared(protectedText.toString(), conditions);
    }

    private static boolean followedByKoreanText(String[] lines, int index) {
        for (int next = index + 1; next < lines.length; next++) {
            String following = lines[next].trim();
            if (following.isEmpty() || LANGUAGE.matcher(following).matches()) continue;
            return hasKorean(following) && !isIngredientTitle(NUMBER_PREFIX.matcher(stripBullet(following)).replaceFirst(""));
        }
        return false;
    }

    private static String stripBullet(String line) { return line.replaceFirst("^[-•*·ㆍ‧∙]+\\s*", ""); }
    private static String removeBracketHeading(String line) {
        if (!line.startsWith("[")) return line;
        int depth = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '[') depth++;
            if (line.charAt(i) == ']' && --depth == 0) {
                String bracket = line.substring(1, i);
                if (bracket.matches("(?s).*(도움|필요|관여|개선|유지|제조|한함|조건|경우).*")) return line;
                return line.substring(i + 1).trim();
            }
        }
        return line;
    }
    private static boolean hasKorean(String text) { return text.matches("(?s).*[가-힣].*"); }
    private static boolean looksLikeTranslation(String text) {
        return stripBullet(text).replaceFirst("^(?:[①-⑳]|\\(?\\d+[.)])\\s*", "")
                .matches("(?i)^(?:may\\s+help|helps?\\b|contributes?\\b|supports?\\b).*" );
    }
    private static boolean isIngredientTitle(String text) {
        if (text.matches("(?s).*(도움|필요|관여|개선|유지|증진|원활|보충).*")) return false;
        return INGREDIENT.matcher(text).matches() || INGREDIENT_NAME.matcher(text).matches();
    }
}
