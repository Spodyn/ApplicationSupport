package com.unifiedsupportinbox.sla.internal;
import com.unifiedsupportinbox.sla.SlaPolicyView;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/sla-policy")
class SlaPolicyAdminController { private final SlaPolicyService policies; SlaPolicyAdminController(SlaPolicyService policies) { this.policies = policies; }
    @GetMapping SlaPolicyView active(Authentication actor) { return policies.active(actor); }
    @PutMapping SlaPolicyView replace(@RequestBody SlaPolicyService.UpdateInput input, Authentication actor) { return policies.replace(actor,input); } }
