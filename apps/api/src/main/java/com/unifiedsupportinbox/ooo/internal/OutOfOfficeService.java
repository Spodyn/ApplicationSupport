package com.unifiedsupportinbox.ooo.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActors;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.ooo.OutOfOfficePolicyView;
import com.unifiedsupportinbox.ooo.OutOfOfficePreviewView;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleCatalog;
import com.unifiedsupportinbox.sla.BusinessTimeCalculator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutOfOfficeService {

    private static final String MANAGE_SCHEDULE = "manage_schedule";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-z_]+)}}");
    private static final Set<String> VARIABLES = Set.of("customer_name", "next_opening_date", "next_opening_time", "timezone");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(java.util.Locale.forLanguageTag("pl"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final OutOfOfficeRepository policies;
    private final BusinessHoursScheduleCatalog schedules;
    private final AuditEventStore auditEvents;
    private final Clock clock;

    @Autowired
    OutOfOfficeService(OutOfOfficeRepository policies, BusinessHoursScheduleCatalog schedules, AuditEventStore auditEvents) {
        this(policies, schedules, auditEvents, Clock.systemUTC());
    }

    OutOfOfficeService(OutOfOfficeRepository policies, BusinessHoursScheduleCatalog schedules, AuditEventStore auditEvents, Clock clock) {
        this.policies = policies;
        this.schedules = schedules;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    OutOfOfficePolicyView get(Authentication actor) {
        requireManageSchedule(actor);
        return policies.get();
    }

    @Transactional
    OutOfOfficePolicyView replace(Authentication actor, UpdateInput input) {
        requireManageSchedule(actor);
        if (input == null || input.version() == null || input.version() < 1) {
            throw ApiProblemException.validationFailed("Out-of-office configuration version must be positive.");
        }
        String template = validateTemplate(input.template());
        OutOfOfficePolicyView updated = policies.update(input.enabled(), template, input.sendOncePerClosure(), input.version(), actor.getName())
                .orElseThrow(() -> ApiProblemException.conflict("Out-of-office configuration changed since it was loaded."));
        auditEvents.append(AuditActors.type(actor), AuditActors.userId(actor), "OUT_OF_OFFICE_UPDATED", "OUT_OF_OFFICE", fixedId(), null,
                Map.of("enabled", updated.enabled(), "sendOncePerClosure", updated.sendOncePerClosure(), "version", updated.version()));
        return updated;
    }

    @Transactional(readOnly = true)
    OutOfOfficePreviewView preview(Authentication actor, String customerName) {
        requireManageSchedule(actor);
        BusinessHoursScheduleView schedule = schedules.findActiveSchedule().orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));
        Instant now = clock.instant();
        Instant opening = new BusinessTimeCalculator().opening(schedule, now).nextOpening();
        return new OutOfOfficePreviewView(render(policies.get().template(), customerName, opening, schedule.timezone()), opening, schedule.timezone());
    }

    private static String validateTemplate(String template) {
        if (template == null || template.isBlank() || template.length() > 4000) {
            throw ApiProblemException.validationFailed("Out-of-office template must contain at most 4000 characters.");
        }
        Matcher matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            if (!VARIABLES.contains(matcher.group(1))) {
                throw ApiProblemException.validationFailed("Out-of-office template contains an unsupported variable.");
            }
        }
        String withoutVariables = VARIABLE.matcher(template).replaceAll("");
        if (withoutVariables.contains("{{") || withoutVariables.contains("}}")) {
            throw ApiProblemException.validationFailed("Out-of-office template contains an invalid variable.");
        }
        return template;
    }

    private static String render(String template, String customerName, Instant opening, String timezone) {
        ZoneId zone = ZoneId.of(timezone);
        String date = opening == null ? "nieustalono" : DATE.withZone(zone).format(opening);
        String time = opening == null ? "nieustalono" : TIME.withZone(zone).format(opening);
        return template.replace("{{customer_name}}", customerName == null || customerName.isBlank() ? "kliencie" : customerName.strip())
                .replace("{{next_opening_date}}", date).replace("{{next_opening_time}}", time).replace("{{timezone}}", timezone);
    }

    private static UUID fixedId() { return new UUID(0, 1); }

    private static void requireManageSchedule(Authentication actor) {
        boolean allowed = actor != null && actor.isAuthenticated() && actor.getAuthorities().stream()
                .anyMatch(authority -> MANAGE_SCHEDULE.equals(authority.getAuthority()));
        if (!allowed) throw ApiProblemException.accessDenied();
    }

    record UpdateInput(boolean enabled, String template, boolean sendOncePerClosure, Long version) { }
}
