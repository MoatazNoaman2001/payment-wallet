# The Auth Module

Phase 4. Before this, identity was a request field:

```json
POST /api/transfers
{ "initiatorPublicId": "d1c0b514-...", "sourceAccountNumber": "PW6180936828619309", "amount": 1600 }
```

The server believed whatever the client claimed, so anyone with a UUID and an account
number could drain that wallet. This module deletes that field and moves identity to
something the server establishes itself.

---

## 1. Three separate questions

Keeping these apart is most of the design.

| Question | Name | Answered by |
|---|---|---|
| Who are you? | authentication | the signed access token |
| What kind of thing may you do? | authorization | roles in the token (`ROLE_CUSTOMER`, `ROLE_ADMIN`) |
| Is *this row* yours? | ownership | `AccountOwnership`, queried per request |

The third is the one tutorials skip. `ROLE_CUSTOMER` grants "read a statement"; it says
nothing about **whose**. Without an ownership check, every authenticated user can read
every account. `Phase4SecurityTest` asserts that a customer gets `403` on someone else's
account *and* that a refused transfer leaves both balances untouched — an authorization
bug that still moves money is worse than one that leaks a read.

---

## 2. Two tokens, two jobs

### Access token — a short-lived JWT

15 minutes, HS256, delivered in an `HttpOnly` cookie:

```
Set-Cookie: access_token=eyJhbGciOiJIUzI1NiJ9...; HttpOnly; SameSite=Lax; Path=/
```

`HttpOnly` means page JavaScript cannot read it, so an XSS payload cannot exfiltrate it.
`Authorization: Bearer` is accepted as a fallback so Swagger and `curl` still work by
hand; a production deployment would drop that path.

Verification is stateless — signature and expiry, no database hit. Two things a JWT is
**not**:

- **Not encrypted.** Anyone holding it can base64-decode the payload. Never put anything
  private in the claims.
- **Not revocable.** It is valid until it expires. That is why the lifetime is short and
  why a refresh token exists.

Claims are `sub` (the user's `publicId`), `email`, `roles`, `exp`.

> `NimbusJwtEncoder` defaults to RS256. With a symmetric secret it fails with
> *"Failed to select a JWK signing key"* until the header names the algorithm:
> `JwsHeader.with(MacAlgorithm.HS256)`.

### Refresh token — opaque, stored, rotating

7–30 days, its own cookie, scoped so the browser sends it nowhere else:

```
Set-Cookie: refresh_token=<256 bits>; HttpOnly; SameSite=Strict; Path=/api/auth/refresh
```

It is **not** a JWT, because this one must be revocable — so it is a database row, and
signing it would buy nothing. It is stored the way passwords are: only a SHA-256 hash, so
a database leak hands out nothing usable.

```sql
CREATE TABLE refresh_token (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    family_id  UUID        NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
```

### Rotation and reuse detection

Every refresh token is **single use**. Presenting one returns a new pair and revokes the
old one. `family_id` groups every token descended from a single login.

```
POST /api/auth/refresh
  ├─ valid and unused  → revoke it, issue a new pair in the same family
  ├─ already revoked   → someone replayed a spent copy
  │                      → revoke the ENTIRE family, force a fresh login
  └─ expired / unknown → 401
```

The middle branch is the point. If a spent token is presented again, either the real
client or an attacker holds an old copy, and there is no way to tell which — so the safe
move is to kill the whole lineage. That gives revocation without a database lookup on
every request; only the refresh, once per 15 minutes, touches the table.

Verified end to end:

```
login HTTP 200
refresh#1 HTTP 200
refresh#2 (replay) HTTP 401
WARN c.m.p.auth.TokenService : Refresh token reuse detected for user 92eaecb5-... -
                              revoked 1 tokens in family 3c75a2aa-...
```

---

## 3. Cookies and the CSRF trade

Putting the token in a cookie buys XSS resistance and costs CSRF exposure: the browser
attaches cookies automatically, so a form on another origin could otherwise make an
authenticated request. The defences, layered:

| Defence | Where |
|---|---|
| `HttpOnly` | scripts cannot read either token |
| `SameSite=Lax` / `Strict` | blocks cross-site form posts |
| `Path=/api/auth/refresh` | the refresh token is not attached to ordinary requests |
| CSRF token | on the browser pages, via `CookieCsrfTokenRepository` |
| CORS allowlist | never `*` together with credentials |

CSRF is deliberately split:

```java
.csrf(csrf -> csrf
        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
        .ignoringRequestMatchers("/api/**"))
```

Pages need it because cookies ride along automatically. `/api/**` does not, because its
clients send an explicit header, and a cross-site form cannot set one. The **cookie**
repository is required rather than the default session-backed one, because this filter
chain is stateless.

---

## 4. Who may do what

Registration only ever grants `ROLE_CUSTOMER`, so staff are seeded: `V5` an administrator
(`admin@paymentwallet.local` / `admin12345`) and `V7` a teller
(`teller@paymentwallet.local` / `teller12345`). Demo credentials only.

| Endpoint | CUSTOMER / MERCHANT | TELLER | ADMIN |
|---|---|---|---|
| `POST /api/users`, `/api/auth/login` | public | public | public |
| `GET /api/users/{publicId}` | self only | self only | anyone |
| `POST /api/users/{id}/activation` | ✗ | ✗ | ✓ — a KYC decision |
| `POST /api/accounts` for self | ✓ | ✓ | ✓ |
| `POST /api/accounts` for another user | ✗ | ✓ | ✓ |
| `GET /api/accounts` (no owner given) | own accounts | **every** account | **every** account |
| `GET /api/accounts/{n}`, `/statement`, `/spend-by-tag` | owner | any | any |
| `POST /…/deposits`, `/withdrawals` | own account | any — counter cash | any |
| `POST /api/transfers` | must own the **source** | ✗ | any |
| `GET`/`PUT` on a transfer | either party to it | — | any |
| `POST /api/transfers/{ref}/reversal` | ✗ | ✗ — back office | ✓ |
| `/api/admin/**` | ✗ | ✗ | ✓ |
| `SYSTEM` settlement accounts | ✗ never | ✗ never | read-only, not creatable via the API |

Two lines there are deliberate. **A teller cannot transfer out of a customer's account**:
taking cash in and paying it out at a counter is a teller operation, but instructing a
payment to a third party is the customer's own act. And **a teller cannot reverse**,
because the person who made the mistake should not be the one who erases it.

A teller opening an account for a customer is recorded in `account.opened_by`, separately
from `user_id`, so the audit trail answers *which employee did this* as well as *whose
account it is*. `transfer.initiated_by` already worked this way.

In a real bank this would go further: system administrators would not hold business
powers at all (segregation of duties), and account opening would be maker–checker — one
employee creates, a second approves. Here `ROLE_ADMIN` keeps the teller powers as a
superset so a demo needs one login rather than three.

Transfers are gated on the **source**: you must own the account money leaves. Anyone may
receive.

`SYSTEM` accounts cannot be opened through the API by anyone, administrators included.
They are exempt from the insufficient-funds check and permitted to hold a negative balance
— that is correct for a settlement account and catastrophic for one an ordinary user can
create. An earlier version of this module accepted `type: SYSTEM` from any authenticated
caller, which let a customer open one and transfer an unbounded amount out of it. Settlement accounts are infrastructure, not user data: `SettlementAccountInitializer`
ensures one exists per currency at every startup, so the system repairs itself if a row is
ever lost. `Phase4SecurityTest` asserts the API refuses to create one.

---

## 5. Status codes

| Situation | Status | Why |
|---|---|---|
| no or bad credentials | `401` | "I do not know who you are" |
| authenticated, not your row | `403` | "I know who you are, and this is not yours" |
| wrong password vs unknown email | `401` **either way** | a distinct reply would enumerate accounts |

The `403` body is deliberately vague — confirming that an account exists but belongs to
someone else still leaks that it exists.

Anonymous requests that explicitly ask for HTML are redirected to `/login` instead:

```java
MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
```

`setIgnoredMediaTypes` is not optional. Without it a request carrying **no** `Accept`
header counts as `*/*`, which is compatible with `text/html`, so API calls would be
redirected to a login page instead of receiving `401`.

---

## 6. Files

| File | Role |
|---|---|
| `AuthService` | one definition of "log in", shared by the JSON API and the pages |
| `AuthController` | `/login`, `/refresh`, `/logout`, `/me` |
| `AuthCookies` | one definition of how the cookies are written |
| `TokenService` | issues access tokens, rotates refresh tokens, detects reuse |
| `RefreshToken` + repository | the stored half |
| `CurrentUser` | the only place identity is read from the security context |
| `AccountOwnership` | the `@ownership` bean referenced from `@PreAuthorize` |
| `SecurityConfig` | filter chain, cookie-or-header token resolver, CSRF split, CORS |

---

## 7. Not done

- **MFA / step-up.** Real payment systems require a second factor for transfers, with the
  code cryptographically bound to the amount and payee ("dynamic linking"), so a
  compromised client cannot swap the recipient after approval.
- **Device binding**, risk scoring, and session policies short enough to matter.
- **Silent refresh for the browser.** The 15-minute access token logs a page user out
  mid-session; a filter that refreshes transparently using the refresh cookie is the fix.
- `@CreatedBy` / `@LastModifiedBy` auditing, now that a principal exists.
- `secure(true)` on the cookies, plus `__Host-` / `__Secure-` name prefixes, once there
  is TLS.
