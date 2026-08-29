package com.unifiedsupportinbox.customer.internal;

import com.unifiedsupportinbox.customer.CustomerView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/customers")
class CustomerAdminController {

    private final CustomerService customers;

    CustomerAdminController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    List<CustomerView> list(Authentication actor) {
        return customers.list(actor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CustomerView create(@Valid @RequestBody CustomerWriteRequest input, Authentication actor) {
        return customers.create(actor, input.name(), input.externalRef());
    }

    @GetMapping("/{customerId}")
    CustomerView get(@PathVariable UUID customerId, Authentication actor) {
        return customers.get(actor, customerId);
    }

    @PutMapping("/{customerId}")
    CustomerView update(
            @PathVariable UUID customerId,
            @Valid @RequestBody CustomerWriteRequest input,
            Authentication actor) {
        return customers.update(actor, customerId, input.name(), input.externalRef());
    }

    @PostMapping("/{customerId}/deactivate")
    CustomerView deactivate(@PathVariable UUID customerId, Authentication actor) {
        return customers.deactivate(actor, customerId);
    }

    record CustomerWriteRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 160) String externalRef) {
    }
}
