package com.unifiedsupportinbox.testing;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates minimal, valid persistence records for backend integration tests.
 *
 * <p>A fixture instance owns a random namespace, making data safe to create in
 * parallel with another suite. Call {@link #cleanup()} in teardown when a test
 * shares a database with other tests.</p>
 */
public final class PersistenceFixtures {

    private final JdbcTemplate jdbc;
    private final String namespace = UUID.randomUUID().toString();

    private User user;
    private Customer customer;
    private Integration integration;
    private Channel channel;

    public PersistenceFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public User user() {
        if (user == null) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO users (email, display_name, password_hash, role, active)
                    VALUES (?, ?, ?, 'USER', TRUE) RETURNING id
                    """, UUID.class, "user-" + namespace + "@example.test", "Test user " + namespace, "not-a-real-password");
            user = new User(id, "user-" + namespace + "@example.test");
        }
        return user;
    }

    public Customer customer() {
        if (customer == null) {
            UUID id = jdbc.queryForObject(
                    "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                    UUID.class,
                    "Test customer " + namespace,
                    "customer-" + namespace);
            customer = new Customer(id, "customer-" + namespace);
        }
        return customer;
    }

    public Integration integration() {
        if (integration == null) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO integrations (
                        provider, display_name, status, health, workspace_external_id, config_json
                    ) VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?, '{}'::jsonb) RETURNING id
                    """, UUID.class, "Test Slack " + namespace, "workspace-" + namespace);
            integration = new Integration(id, "workspace-" + namespace);
        }
        return integration;
    }

    public Channel channel() {
        if (channel == null) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO channels (
                        integration_id, external_channel_id, name, customer_id, grouping_strategy
                    ) VALUES (?, ?, ?, ?, 'SLACK_ROOT_THREAD') RETURNING id
                    """, UUID.class, integration().id(), "channel-" + namespace,
                    "Test channel " + namespace, customer().id());
            channel = new Channel(id, "channel-" + namespace);
        }
        return channel;
    }

    /** Removes this fixture's records in foreign-key order. Safe to call more than once. */
    public void cleanup() {
        if (channel != null) jdbc.update("DELETE FROM channels WHERE id = ?", channel.id());
        if (integration != null) jdbc.update("DELETE FROM integrations WHERE id = ?", integration.id());
        if (customer != null) jdbc.update("DELETE FROM customers WHERE id = ?", customer.id());
        if (user != null) jdbc.update("DELETE FROM users WHERE id = ?", user.id());
    }

    public record User(UUID id, String email) { }
    public record Customer(UUID id, String externalRef) { }
    public record Integration(UUID id, String workspaceExternalId) { }
    public record Channel(UUID id, String externalChannelId) { }
}
