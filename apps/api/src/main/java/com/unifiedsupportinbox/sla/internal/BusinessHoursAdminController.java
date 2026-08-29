package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/business-hours")
class BusinessHoursAdminController {

    private final BusinessHoursService businessHours;

    BusinessHoursAdminController(BusinessHoursService businessHours) {
        this.businessHours = businessHours;
    }

    @GetMapping
    BusinessHoursScheduleView get(Authentication actor) {
        return businessHours.getActive(actor);
    }

    @PutMapping
    BusinessHoursScheduleView replace(
            @RequestBody UpdateBusinessHoursRequest input,
            Authentication actor) {
        List<BusinessHoursService.IntervalInput> intervals = input == null || input.intervals() == null
                ? null
                : input.intervals().stream()
                        .map(interval -> interval == null
                                ? null
                                : new BusinessHoursService.IntervalInput(
                                        interval.dayOfWeek(), interval.start(), interval.end()))
                        .toList();
        return businessHours.replaceActive(
                actor,
                input == null ? null : input.timezone(),
                intervals);
    }

    record UpdateBusinessHoursRequest(String timezone, List<IntervalRequest> intervals) {
    }

    record IntervalRequest(Integer dayOfWeek, String start, String end) {
    }
}
