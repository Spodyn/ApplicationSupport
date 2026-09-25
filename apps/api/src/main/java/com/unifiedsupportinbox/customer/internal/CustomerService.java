package com.unifiedsupportinbox.customer.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActors;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.customer.CustomerView;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CustomerService {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private final CustomerRepository customers;
    private final AuditEventStore auditEvents;

    CustomerService(CustomerRepository customers, AuditEventStore auditEvents) {
        this.customers = customers;
        this.auditEvents = auditEvents;
    }

    @Transactional(readOnly = true)
    List<CustomerView> list(Authentication actor) {
        requireAdmin(actor);
        return customers.findAllByOrderByNameAscIdAsc().stream().map(CustomerService::view).toList();
    }

    @Transactional(readOnly = true)
    CustomerView get(Authentication actor, UUID customerId) {
        requireAdmin(actor);
        return view(required(customerId));
    }

    @Transactional
    CustomerView create(Authentication actor, String name, String externalRef) {
        requireAdmin(actor);
        try {
            CustomerEntity created = customers.saveAndFlush(new CustomerEntity(name, externalRef));
            appendAudit(actor, created, "CUSTOMER_CREATED");
            return view(created);
        } catch (DataIntegrityViolationException exception) {
            throw duplicate();
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed(exception.getMessage());
        }
    }

    @Transactional
    CustomerView update(Authentication actor, UUID customerId, String name, String externalRef) {
        requireAdmin(actor);
        CustomerEntity customer = required(customerId);
        try {
            customer.update(name, externalRef);
            CustomerEntity updated = customers.saveAndFlush(customer);
            appendAudit(actor, updated, "CUSTOMER_UPDATED");
            return view(updated);
        } catch (DataIntegrityViolationException exception) {
            throw duplicate();
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed(exception.getMessage());
        }
    }

    @Transactional
    CustomerView deactivate(Authentication actor, UUID customerId) {
        requireAdmin(actor);
        CustomerEntity customer = required(customerId);
        customer.deactivate();
        CustomerEntity deactivated = customers.saveAndFlush(customer);
        appendAudit(actor, deactivated, "CUSTOMER_DEACTIVATED");
        return view(deactivated);
    }

    private CustomerEntity required(UUID customerId) {
        return customers.findById(customerId)
                .orElseThrow(() -> ApiProblemException.notFound("Customer was not found."));
    }

    private static void requireAdmin(Authentication actor) {
        boolean admin = actor != null
                && actor.isAuthenticated()
                && actor.getAuthorities().stream().anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()));
        if (!admin) throw ApiProblemException.accessDenied();
    }

    private static ApiProblemException duplicate() {
        return ApiProblemException.conflict("A customer with this name or external reference already exists.");
    }

    private void appendAudit(Authentication actor, CustomerEntity customer, String action) {
        auditEvents.append(
                AuditActors.type(actor),
                AuditActors.userId(actor),
                action,
                "CUSTOMER",
                customer.id(),
                null,
                java.util.Map.of(
                        "name", customer.name(),
                        "externalRef", customer.externalRef(),
                        "active", customer.active()));
    }

    private static CustomerView view(CustomerEntity customer) {
        return new CustomerView(
                customer.id(), customer.name(), customer.externalRef(), customer.active(),
                customer.createdAt(), customer.updatedAt());
    }
}
