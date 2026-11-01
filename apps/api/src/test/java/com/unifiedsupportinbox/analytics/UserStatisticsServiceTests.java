package com.unifiedsupportinbox.analytics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class UserStatisticsServiceTests {

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Authentication session(UUID id, String... authorities) {
        return new UsernamePasswordAuthenticationToken(id.toString(), null,
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void anyAuthenticatedUserCanReadOnlyTheirOwnHistoricalStatistics() {
        assertThatCode(() -> UserStatisticsService.authorize(session(ACTOR, "ROLE_USER"), ACTOR))
                .doesNotThrowAnyException();
        assertThatCode(() -> UserStatisticsService.authorize(session(ACTOR, "ROLE_ADMIN"), ACTOR))
                .doesNotThrowAnyException();
    }

    @Test
    void otherUserStatisticsRequireBothAdminRoleAndGlobalPermission() {
        assertThatCode(() -> UserStatisticsService.authorize(
                session(ACTOR, "ROLE_ADMIN", "view_global_statistics"), OTHER))
                .doesNotThrowAnyException();
        for (Authentication denied : List.of(
                session(ACTOR, "ROLE_USER"),
                session(ACTOR, "ROLE_USER", "view_global_statistics"),
                session(ACTOR, "ROLE_ADMIN"))) {
            assertThatThrownBy(() -> UserStatisticsService.authorize(denied, OTHER))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            error -> org.assertj.core.api.Assertions.assertThat(error.status())
                                    .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    @Test
    void anonymousAndMissingIdentityAreNeverAllowed() {
        assertThatThrownBy(() -> UserStatisticsService.authorize(null, ACTOR))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.status())
                                .isEqualTo(HttpStatus.UNAUTHORIZED));
        Authentication invalid = new UsernamePasswordAuthenticationToken("not-a-user-id", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("view_global_statistics")));
        assertThatThrownBy(() -> UserStatisticsService.authorize(invalid, ACTOR))
                .isInstanceOf(ApiProblemException.class);
    }
}
