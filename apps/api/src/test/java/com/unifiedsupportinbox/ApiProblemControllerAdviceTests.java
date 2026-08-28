package com.unifiedsupportinbox;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ApiProblemControllerAdviceTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ApiProblemControllerAdvice(new ApiProblemFactory()))
                .build();
    }

    @Test
    void validationUsesStableProblemContractWithoutRejectedValues() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.correlationId", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(content().string(not(containsString("rejectedValue"))));
    }

    @Test
    void invalidCursorUsesStable400CodeAndSafeDetail() throws Exception {
        mockMvc.perform(get("/test/invalid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.detail").value("The pagination cursor has expired."));
    }

    @Test
    void authenticationUsesStable401Code() throws Exception {
        assertControlled("/test/auth", 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void permissionUsesStable403Code() throws Exception {
        assertControlled("/test/permission", 403, "ACCESS_DENIED");
    }

    @Test
    void notFoundUsesStable404Code() throws Exception {
        assertControlled("/test/not-found", 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void conflictUsesStable409Code() throws Exception {
        assertControlled("/test/conflict", 409, "CONFLICT");
    }

    @Test
    void rateLimitUsesStable429Code() throws Exception {
        assertControlled("/test/rate-limit", 429, "RATE_LIMITED");
    }

    @Test
    void providerAndApplicationFailuresNeverExposeCauseMessages() throws Exception {
        mockMvc.perform(get("/test/provider-failure"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVIDER_FAILURE"))
                .andExpect(content().string(not(containsString("provider-secret-payload"))));

        mockMvc.perform(get("/test/application-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("APPLICATION_FAILURE"))
                .andExpect(content().string(not(containsString("application-secret-payload"))));
    }

    @Test
    void fallback500DoesNotExposeExceptionOrStackTrace() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("sensitive-provider-token"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    private void assertControlled(String path, int expectedStatus, String expectedCode) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.correlationId", matchesPattern("[0-9a-f-]{36}")));
    }

    @RestController
    static class TestController {

        @PostMapping("/test/validation")
        void validation(@Valid @RequestBody ValidationRequest request) {
        }

        @GetMapping("/test/invalid-cursor")
        void invalidCursor() {
            throw new InvalidCursorException(InvalidCursorException.Reason.EXPIRED);
        }

        @GetMapping("/test/auth")
        void authentication() {
            throw ApiProblemException.authenticationRequired();
        }

        @GetMapping("/test/permission")
        void permission() {
            throw ApiProblemException.accessDenied();
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw ApiProblemException.notFound("The requested case was not found.");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw ApiProblemException.conflict("The case changed before this command completed.");
        }

        @GetMapping("/test/rate-limit")
        void rateLimit() {
            throw ApiProblemException.rateLimited("Too many requests were made. Try again later.");
        }

        @GetMapping("/test/provider-failure")
        void providerFailure() {
            throw ApiProblemException.providerFailure(new IllegalStateException("provider-secret-payload"));
        }

        @GetMapping("/test/application-failure")
        void applicationFailure() {
            throw ApiProblemException.applicationFailure(new IllegalStateException("application-secret-payload"));
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("sensitive-provider-token");
        }
    }

    record ValidationRequest(@NotBlank String name) {
    }
}
