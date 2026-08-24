# Kontrakt uwierzytelniania

**Status:** FROZEN v1, reconciled 24 sierpnia 2026.  
**Authority:** `PRODUCT_CONTRACT.md`, `decision-registry.yaml`, USI-33 oraz późniejszy E04 pre-flight freeze.

## Baseline v1

- Uwierzytelnianie: lokalny e-mail + hasło.
- Kanoniczny użytkownik jest jeden; `USER` i `ADMIN` logują się tym samym mechanizmem.
- Sesja jest server-side przez Spring Session JDBC.
- Future OIDC/SSO mapuje do tej samej lokalnej identity/session i nie nadaje roli ani permissions z nieufnych IdP claims.

## Normalizacja konta

- e-mail: trim + lowercase przed persistence/login lookup,
- normalized e-mail: UNIQUE,
- `display_name` jest osobnym polem,
- konto inactive albo poza `valid_from`/`valid_until` nie może utworzyć ani utrzymać ważnej sesji.

## Cookie i sesja

Kanoniczne cookie v1:

- name: `USI_SESSION`,
- `HttpOnly`,
- `Secure` na staging i production,
- `SameSite=Lax`,
- `Path=/`,
- idle timeout: 12 godzin,
- brak remember-me w v1.

Logout natychmiast unieważnia server session. Session fixation protection i rotacja identyfikatora po uwierzytelnieniu są wymagane. Dezaktywacja/wygaśnięcie konta oraz skuteczny password reset unieważniają istniejące sesje zgodnie z kontraktem.

## Password policy

- hashing: Argon2id,
- długość: 12-128 Unicode characters,
- brak sztucznego obowiązku upper/lower/digit/symbol,
- brak okresowej rotacji,
- brak security questions,
- generic login failure bez account enumeration,
- rehash przy poprawnym loginie, gdy parametry hasha są już nieaktualne.

Plaintext password nie trafia do DB, logów, audit, events ani command line.

## Invitation

- minimum 256-bit CSPRNG entropy,
- plaintext istnieje tylko przy generacji,
- DB przechowuje wyłącznie hash,
- TTL: 24 godziny,
- one-time use,
- nowy invite unieważnia wcześniejsze aktywne invite tego usera,
- user nie loguje się przed ustawieniem pierwszego hasła.

V1 nie wymaga produkcyjnego e-mail providera do dostarczenia invite; dev/test może użyć kontrolowanego kanału testowego.

## Password reset

- minimum 256-bit CSPRNG entropy,
- hash-only persistence,
- TTL: 30 minut,
- nowy reset invaliduje wcześniejsze aktywne reset tokens,
- request endpoint zwraca neutralną odpowiedź,
- sukces invaliduje wszystkie reset tokens i wszystkie istniejące sessions tego usera.

## Pierwszy administrator

Bootstrap pierwszego `ADMIN` odbywa się przez kontrolowaną lokalną/server operational command, nigdy publiczny unauthenticated endpoint.

- działa tylko, gdy nie istnieje aktywny ADMIN,
- e-mail może być podany jako argument/env,
- hasło przez stdin/secret input, nie command-line argument,
- credentiali nie logować,
- drugi bootstrap jest odrzucany.

## API boundary

Publiczne API pozostaje pod `/api/v1`:

- `POST /api/v1/auth/login`,
- `POST /api/v1/auth/logout`,
- `GET /api/v1/auth/me`,
- dedykowane invite/password-reset commands zgodne z OpenAPI.

`/auth/me` zwraca kanoniczny user ID, rolę i effective permissions, bez password material.

## CSRF i browser storage

Każda state-changing browser mutation uwierzytelniana cookie wymaga CSRF protection. SameSite/Origin/Referer mogą wzmacniać ochronę, ale nie zastępują uzgodnionej ochrony CSRF.

Frontend nie przechowuje session identifiers, haseł ani action tokens w `localStorage`, `sessionStorage`, IndexedDB ani Cache Storage. PWA service worker nie cache'uje authenticated `/api/**` responses.

## Authorization boundary

Admin capability zwykle wymaga jednocześnie roli `ADMIN` i odpowiedniego granular permission. `USER + permission` nie daje capability administracyjnego. General settings pozostaje ADMIN-only bez dodatkowego permission.

## Testy wymagane przy implementacji

- valid/invalid login bez enumeracji,
- cookie flags, session rotation/persistence/logout,
- inactive/expired account denial,
- CSRF positive/negative,
- password hash/rehash policy,
- invite/reset entropy, hash, TTL, replay i invalidation,
- password reset session invalidation,
- bootstrap first-admin safety,
- brak session/action tokens w browser storage/cache,
- OIDC mapping do tej samej identity bez zaufania do IdP permissions.

Szczegóły Spring Security classes, controller composition i test harness są delegowanymi decyzjami technicznymi, jeśli zachowują powyższy kontrakt.