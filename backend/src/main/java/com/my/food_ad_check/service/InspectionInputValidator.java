package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.InspectionJobRequest;
import com.my.food_ad_check.entity.Product;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Layer 1 checks inspectability only. Advertising content is judged by the judge service. */
@Service
public class InspectionInputValidator {
    public static final int MAX_CHARACTERS = 3000;
    public record Check(String code, String label, boolean passed) { }
    public record Result(String kind, String status, String checkedAt, Integer characterCount,
                         int maxCharacters, List<Check> checks, List<String> notices) { }
    public record Input(List<String> productIds, String productTypeAnswer, String adText, int characterCount) { }

    public Input validate(InspectionJobRequest request) {
        if (request == null) throw new InspectionInputException(HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_BODY", "검수할 광고 문구를 입력해 주세요.", null);
        int count = validateText(request.adText());
        List<String> ids = request.productIds() == null ? List.of() : request.productIds();
        if (ids.size() > 1 || (ids.size() == 1 && (ids.get(0) == null || ids.get(0).isBlank()))) {
            throw new InspectionInputException(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_SELECTION",
                    "검사 한 번에 제품 하나만 선택해 주세요.", count);
        }
        if (request.productTypeAnswer() != null && !List.of(
                "HEALTH_FUNCTIONAL", "FUNCTIONAL_GENERAL", "GENERAL", "UNKNOWN").contains(request.productTypeAnswer())) {
            throw new InspectionInputException(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_TYPE",
                    "알 수 없는 제품 유형 답변입니다.", count);
        }
        return new Input(ids.isEmpty() ? List.of() : List.of(ids.get(0).trim()),
                request.productTypeAnswer(), request.adText().trim(), count);
    }

    public static int validateText(String text) {
        int count = text == null ? 0 : text.codePointCount(0, text.length());
        if (text == null || text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
            throw new InspectionInputException(HttpStatus.BAD_REQUEST, "EMPTY_AD_TEXT",
                    "검수할 광고 문구를 입력해 주세요.", count);
        }
        if (count > MAX_CHARACTERS) {
            throw new InspectionInputException(HttpStatus.BAD_REQUEST, "AD_TEXT_TOO_LONG",
                    "광고 문구는 공백·줄바꿈을 포함해 3,000자까지 입력할 수 있습니다.", count);
        }
        return count;
    }

    public void validateProduct(Product product, int count) {
        if (product != null && product.isFunctionSourceReviewRequired()) {
            throw new InspectionInputException(HttpStatus.CONFLICT, "PRODUCT_DATA_NOT_READY",
                    "최신 기능성 목록을 자동 추출하지 못해 검사를 보류했습니다. 데이터 갱신 후 다시 시도해 주세요.", count);
        }
    }

    public Result passed(int count, String productSource) {
        List<String> notices = switch (productSource) {
            case "UNVERIFIED" -> List.of("제품 정보를 확인하지 못해 일부 판정은 보류될 수 있습니다.");
            case "USER_DECLARED" -> List.of("사용자가 선택한 제품 종류를 기준으로 검수합니다.");
            default -> List.of();
        };
        return new Result("INPUT_VALIDATION", "PASSED", OffsetDateTime.now().toString(), count, MAX_CHARACTERS,
                List.of(new Check("AD_TEXT_PRESENT", "광고 문구 입력", true),
                        new Check("AD_TEXT_LENGTH", "3,000자 이내", true),
                        new Check("REQUEST_FORMAT", "제품 선택·유형 입력 형식", true),
                        new Check("PRODUCT_DATA", "접수 시 제품 데이터 사용 조건", true)), notices);
    }

    public static Result rejected(String code, String message, Integer count) {
        return new Result("INPUT_VALIDATION", "REJECTED", OffsetDateTime.now().toString(), count, MAX_CHARACTERS,
                List.of(new Check(code, message, false)), List.of());
    }
}
