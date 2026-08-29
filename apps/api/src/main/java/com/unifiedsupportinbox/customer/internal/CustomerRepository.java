package com.unifiedsupportinbox.customer.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {
    List<CustomerEntity> findAllByOrderByNameAscIdAsc();
}
