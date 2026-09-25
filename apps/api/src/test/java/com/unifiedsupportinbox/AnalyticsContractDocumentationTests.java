package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnalyticsContractDocumentationTests {

    @Test
    void analyticsContractFreezesCanonicalEventsFormulasAndEdgeCases() throws IOException {
        String contract = Files.readString(Path.of("..", "..", "docs", "ANALYTICS_CONTRACT.md"));

        assertThat(contract)
                .contains("CASE_CREATED")
                .contains("CASE_CLAIMED")
                .contains("FIRST_RESPONSE_SENT")
                .contains("CASE_RESOLVED")
                .contains("SLA_WARNING")
                .contains("SLA_BREACH")
                .contains("p50")
                .contains("p90")
                .contains("p95")
                .contains("DST")
                .contains("force-resolved")
                .contains("Admin assignment/reassignment is not a Claim")
                .contains("no-response Cases remain pending")
                .contains("never manufacture `0` minutes or `100%`");
    }
}
