package com.my.food_ad_check.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovedFunctionsReconciliationTests {

    @Test
    void onlyAnExactPreviousListCanBeReplaced() {
        List<String> previous = List.of("비타민D", "(가) 칼슘과 인이 흡수되고 이용되는데 필요");

        assertTrue(ApprovedFunctionsReconciliation.matchesPrevious(List.of(
                new ApprovedFunctionsReconciliation.StoredFunction(0, "비타민D"),
                new ApprovedFunctionsReconciliation.StoredFunction(1,
                        "(가) 칼슘과 인이 흡수되고 이용되는데 필요")), previous));
        assertFalse(ApprovedFunctionsReconciliation.matchesPrevious(List.of(
                new ApprovedFunctionsReconciliation.StoredFunction(0, "수동 검수 문구")), previous));
        assertFalse(ApprovedFunctionsReconciliation.matchesPrevious(List.of(
                new ApprovedFunctionsReconciliation.StoredFunction(1, "비타민D"),
                new ApprovedFunctionsReconciliation.StoredFunction(2,
                        "(가) 칼슘과 인이 흡수되고 이용되는데 필요")), previous));
    }

    @Test
    void allowsOnlyHeadingRemovalOrLeadingMarkerRemoval() {
        List<String> previous = List.of("비타민D", "(가) 칼슘과 인이 흡수되고 이용되는데 필요");

        assertTrue(ApprovedFunctionsReconciliation.isConservativeChange(previous,
                List.of("칼슘과 인이 흡수되고 이용되는데 필요")));
        assertFalse(ApprovedFunctionsReconciliation.isConservativeChange(previous,
                List.of("칼슘 흡수에 도움")));
    }
}
