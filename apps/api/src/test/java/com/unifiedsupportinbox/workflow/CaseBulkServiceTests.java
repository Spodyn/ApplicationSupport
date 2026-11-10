package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.*;
import com.unifiedsupportinbox.ApiProblemException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CaseBulkServiceTests {
    @Test
    void rejectsEmptyOversizedDuplicateNullAndInvalidTargetSelections() {
        UUID id = UUID.randomUUID();
        for (var request : List.of(
                new CaseBulkService.Request(List.of(), CaseBulkService.Action.CLAIM, null),
                new CaseBulkService.Request(IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList(), CaseBulkService.Action.CLAIM, null),
                new CaseBulkService.Request(List.of(id, id), CaseBulkService.Action.CLAIM, null),
                new CaseBulkService.Request(Collections.singletonList(null), CaseBulkService.Action.CLAIM, null),
                new CaseBulkService.Request(List.of(id), CaseBulkService.Action.ASSIGN, null),
                new CaseBulkService.Request(List.of(id), CaseBulkService.Action.RESOLVE, id),
                new CaseBulkService.Request(List.of(id), null, null))) {
            assertThatThrownBy(() -> CaseBulkService.validate(request)).isInstanceOf(ApiProblemException.class);
        }
        assertThatThrownBy(() -> CaseBulkService.validate(null)).isInstanceOf(ApiProblemException.class);
        assertThatCode(() -> CaseBulkService.validate(new CaseBulkService.Request(
                IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList(), CaseBulkService.Action.CLAIM, null))).doesNotThrowAnyException();
    }
}
