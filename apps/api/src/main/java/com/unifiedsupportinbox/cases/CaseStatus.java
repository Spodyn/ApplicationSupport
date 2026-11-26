package com.unifiedsupportinbox.cases;

public enum CaseStatus {
    NEW,
    VERIFICATION,
    WAITING_FOR_CUSTOMER,
    PARTIALLY_IGNORED,
    IGNORED,
    RESOLVED;

    public boolean acceptsOwnerWork() {
        return this == VERIFICATION || this == WAITING_FOR_CUSTOMER;
    }
}
