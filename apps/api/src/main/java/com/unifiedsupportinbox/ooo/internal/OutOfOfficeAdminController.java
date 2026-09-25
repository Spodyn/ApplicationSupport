package com.unifiedsupportinbox.ooo.internal;

import com.unifiedsupportinbox.ooo.OutOfOfficePolicyView;
import com.unifiedsupportinbox.ooo.OutOfOfficePreviewView;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/out-of-office")
class OutOfOfficeAdminController {
    private final OutOfOfficeService outOfOffice;
    OutOfOfficeAdminController(OutOfOfficeService outOfOffice) { this.outOfOffice = outOfOffice; }
    @GetMapping OutOfOfficePolicyView get(Authentication actor) { return outOfOffice.get(actor); }
    @PutMapping OutOfOfficePolicyView replace(@RequestBody OutOfOfficeService.UpdateInput input, Authentication actor) { return outOfOffice.replace(actor, input); }
    @GetMapping("/preview") OutOfOfficePreviewView preview(@RequestParam(required = false) String customerName, Authentication actor) { return outOfOffice.preview(actor, customerName); }
}
