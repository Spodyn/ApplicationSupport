package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.identity.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only user directory; never serializes credentials or session material. */
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserListController {

    private final AdminUserListService users;

    AdminUserListController(AdminUserListService users) {
        this.users = users;
    }

    @GetMapping
    CursorPage<AdminUserListService.AdminUserItem> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        return users.list(authentication, search, role, active, cursor, limit);
    }
}
