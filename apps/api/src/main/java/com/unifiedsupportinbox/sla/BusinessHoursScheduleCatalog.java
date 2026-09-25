package com.unifiedsupportinbox.sla;

import java.util.Optional;

/** Read-only access to the active schedule for modules that must not own schedule persistence. */
public interface BusinessHoursScheduleCatalog {
    Optional<BusinessHoursScheduleView> findActiveSchedule();
}
