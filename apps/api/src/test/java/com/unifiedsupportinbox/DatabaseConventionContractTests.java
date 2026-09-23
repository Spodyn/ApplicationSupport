package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class DatabaseConventionContractTests {

    private static final Pattern BARE_TIMESTAMP = Pattern.compile(
            "(?i)(?<![a-z0-9_])timestamp(?![a-z0-9_])");

    private final PathMatchingResourcePatternResolver resources =
            new PathMatchingResourcePatternResolver();

    @Test
    void realFlywayMigrationsNeverDeclareTimestampWithoutTimeZone() throws IOException {
        Resource[] migrations = resources.getResources("classpath*:db/migration/V*.sql");
        assertThat(migrations).isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Resource migration : migrations) {
            String sql = stripLineComments(read(migration));
            if (BARE_TIMESTAMP.matcher(sql).find()) {
                violations.add(migration.getFilename());
            }
        }

        assertThat(violations)
                .as("absolute moments must use timestamptz, never timestamp without time zone")
                .isEmpty();
    }

    @Test
    void referenceMigrationDemonstratesFrozenDatabaseConventions() throws IOException {
        Resource example = resources.getResource(
                "classpath:db/conventions/database_conventions_example.sql");
        String sql = read(example).toLowerCase();

        assertThat(sql).contains("id uuid not null default uuidv7()");
        assertThat(sql).contains("created_at timestamptz");
        assertThat(sql).contains("updated_at timestamptz");
        assertThat(sql).contains("version bigint not null default 0");
        assertThat(sql).contains("create sequence case_reference_example_seq");
        assertThat(sql).contains("'case-' || lpad(nextval('case_reference_example_seq')::text, 8, '0')");
        assertThat(sql).contains("constraint pk_case_record_example primary key");
        assertThat(sql).contains("constraint fk_case_record_example_customer");
        assertThat(sql).contains("constraint uq_case_record_example_reference unique");
        assertThat(sql).contains("constraint ck_case_record_example_status check");
        assertThat(sql).contains("create index idx_case_record_example_status_updated");
        assertThat(BARE_TIMESTAMP.matcher(stripLineComments(sql)).find()).isFalse();
    }

    private static String read(Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String stripLineComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment >= 0 ? line.substring(0, comment) : line;
                })
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
