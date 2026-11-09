package com.unifiedsupportinbox.customer;

import java.util.UUID;

/** Minimal customer eligibility boundary used by channel administration. */
public interface CustomerDirectory {

    boolean isActive(UUID customerId);
}
