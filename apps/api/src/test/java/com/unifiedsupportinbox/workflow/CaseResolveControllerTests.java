package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemCode;
import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.cases.CaseResolutionCategory;
import org.junit.jupiter.api.Test;

class CaseResolveControllerTests {

    @Test
    void acceptsOnlyFrozenOptionalResolutionCategories() {
        assertThat(CaseResolveController.resolutionCategory(null)).isNull();
        assertThat(CaseResolveController.resolutionCategory("SOLVED"))
                .isEqualTo(CaseResolutionCategory.SOLVED);
        assertThat(CaseResolveController.resolutionCategory(" no_action_required "))
                .isEqualTo(CaseResolutionCategory.NO_ACTION_REQUIRED);

        for (String invalid : new String[] {"", "CUSTOM", "resolved"}) {
            assertThatThrownBy(() -> CaseResolveController.resolutionCategory(invalid))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            error -> assertThat(error.code()).isEqualTo(ApiProblemCode.VALIDATION_FAILED));
        }
    }
}
