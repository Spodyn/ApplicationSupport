package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases/filter-options")
class CaseFilterOptionsController {
    private final JdbcTemplate jdbc;

    CaseFilterOptionsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    FilterOptions options(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiProblemException.authenticationRequired();
        }
        List<Option> customers = jdbc.query("""
                SELECT DISTINCT customer.id, customer.name
                FROM customers customer
                JOIN cases current_case ON current_case.customer_id = customer.id
                ORDER BY customer.name, customer.id
                """, (rs, row) -> new Option(rs.getObject("id", UUID.class), rs.getString("name")));
        List<Option> channels = jdbc.query("""
                SELECT DISTINCT channel.id, channel.name
                FROM channels channel
                JOIN cases current_case ON current_case.channel_id = channel.id
                ORDER BY channel.name, channel.id
                """, (rs, row) -> new Option(rs.getObject("id", UUID.class), rs.getString("name")));
        List<Option> owners = jdbc.query("""
                SELECT id, display_name
                FROM users
                WHERE active = TRUE
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
                  AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                ORDER BY display_name, id
                """, (rs, row) -> new Option(rs.getObject("id", UUID.class), rs.getString("display_name")));
        return new FilterOptions(customers, channels, owners, List.of("SLACK", "TEAMS", "TELEGRAM"));
    }

    record Option(UUID id, String name) {}
    record FilterOptions(List<Option> customers, List<Option> channels, List<Option> owners, List<String> providers) {}
}
