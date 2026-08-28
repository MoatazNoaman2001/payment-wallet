# The User Module — Onboarding and KYC

Who gets to exist in this system, who decides they are real, and what that decision buys them.

Before this, `kyc_profile` was a table from `V1__init.sql` that no line of code ever read or
wrote. Registration was self-service, everyone landed on `PENDING`, and an administrator
flipped them to `ACTIVE` with a button that consulted nothing.

---

## 1. Two ways in

Registration is the only write in the whole application with no `@PreAuthorize` on it, and it
has to be: you cannot authenticate as a user who does not exist yet.

| | Self-service | Assisted |
|---|---|---|
| Route | `POST /api/users`, `/register` | `POST /api/users/counter`, `/users/new` |
| Caller | anybody, signed out | a signed-in employee |
| Who keys it in | the customer | a teller at the branch |
| Identity evidence | submitted later, by them | checked in person, keyed in at the same time |
| `app_user.registered_by` | `NULL` | the employee |
| Resulting status | `PENDING` | `PENDING` |

Both end in the same place, and that is the point: **being registered is not being believed.**

`registered_by` closes a hole in the audit trail. `account.opened_by` and
`transfer.initiated_by` already recorded which employee acted; the user row — where the trail
starts — recorded nothing. If a fake customer turns up in the database, the question "did they
sign themselves up, or did an employee create them?" is the one a fraud investigation asks
first, and until now the schema could not answer it.

Note that self-service registration still records a registrar when a signed-in employee happens
to call it:

```java
UserResponse created = userService.register(request,
        currentUser.publicIdIfPresent().orElse(null));
```

`publicIdIfPresent` rather than `publicId`, because the endpoint is public and the usual case
is nobody being logged in at all.

---

## 2. The lifecycle

```
register ──▶ PENDING, no file
                │
                │  PUT /api/users/{id}/kyc          (the holder, or an employee for them)
                ▼
           PENDING, file at BASIC, verified_at NULL      ◀── still no money movement
                │
                │  POST /api/users/{id}/kyc-review  (COMPLIANCE or ADMIN)
                ▼
           ACTIVE, tier set, verified_at + reviewed_by stamped
```

Approval is one call because it is one decision. The tier and the activation are written in a
single transaction — there is no state where somebody is `ACTIVE` at a tier nobody chose:

```java
profile.setTier(tier);
profile.setVerifiedAt(OffsetDateTime.now(ZoneOffset.UTC));
profile.setReviewedBy(reviewer);
subject.setStatus(UserStatus.ACTIVE);
```

Three rules sit around it:

- **You cannot review an empty file.** Approving somebody who submitted nothing would be
  activation wearing a KYC costume, which is what the old button was.
- **You cannot verify yourself.** Same principle as the teller who may not serve their own
  account — the check is on identity, not on role.
- **Verified details are frozen to the holder.** Until review they can correct a typo; after
  it, changing the national id on a verified file has to go back through compliance.

---

## 3. Compliance, not administration

Activation used to be `hasRole('ADMIN')`. It is now:

```java
public boolean canApproveIdentity(Authentication authentication) {
    return hasAnyRole(authentication, Set.of(Role.COMPLIANCE, Role.ADMIN));
}
```

Deciding that a person is who they claim to be is a compliance judgement about a human being,
not a systems-administration task. It sits beside `canFreeze` for exactly the same reason.

A teller may register a walk-in and key in the id they just held in their hand. They may not
then declare it good. One employee gathers evidence, a different function accepts it — the
same maker–checker split that keeps a teller from reversing their own mistake.

`ROLE_ADMIN` keeps the power as a superset so the demo needs one login rather than three. A
real bank would take it away.

---

## 4. Tiers, and why a wallet has them

Every regulated jurisdiction requires a wallet to identify its holders. Almost none require the
same evidence from everybody, because a wallet holding 500 EGP is a poor money-laundering
vehicle and demanding a passport to receive pocket money kills adoption for no benefit.

So the evidence and the allowance move together:

| Tier | What it means | Default daily ceiling |
|---|---|---|
| `BASIC` | name and phone, nothing checked | 5,000 |
| `VERIFIED` | national id checked | 50,000 |
| `ENHANCED` | id plus address, business use | no ceiling |

```yaml
limits:
  kyc:
    basic: 5000
    verified: 50000
    enhanced: 0     # 0 means no ceiling
```

**How much you may move is a function of how well you are known.** That one sentence is the
principle behind every limit you have ever hit as a customer of a real wallet.

### Where it bites

`TransferSupport.validate` already checked `account.daily_limit`. Now the account limit and the
tier ceiling are resolved together and the **tighter one wins**:

```java
private DailyCap dailyCap(Account source) {
    if (source.getType() == AccountType.SYSTEM) {
        return null;
    }
    DailyCap cap = source.getDailyLimit() == null
            ? null
            : new DailyCap(source.getDailyLimit(), "account limit");

    KycTier tier = kycLimits.effectiveTier(source.getUser());
    BigDecimal ceiling = kycLimits.ceilingFor(tier).orElse(null);
    if (ceiling != null && (cap == null || ceiling.compareTo(cap.amount()) < 0)) {
        cap = new DailyCap(ceiling, tier + " verification");
    }
    return cap;
}
```

The cap carries a reason so the refusal can say which rule stopped it. "Daily limit exceeded"
alone leaves a customer with no idea whether to wait until tomorrow or go and get verified:

```
Daily limit exceeded: 0 already sent, limit 5000 (BASIC verification)
Daily limit exceeded: 0 already sent, limit 100.0000 (account limit)
```

Two exemptions. `SYSTEM` settlement accounts have no ceiling — they are infrastructure, and
capping them would cap every deposit in that currency at once. And an unreviewed or missing
profile resolves to `BASIC` rather than to "unlimited", so the failure mode of a gap in the
data is a customer who is too restricted, never one who is too free.

> Existing account holders from before this change have no profile, so they drop to `BASIC`
> until somebody reviews them. That is what re-KYC looks like in production too.

---

## 5. The national id is not a lookup service

It comes back masked, everywhere:

```java
public String maskedNationalId() {
    if (nationalId == null || nationalId.length() <= 4) {
        return "****";
    }
    return "*".repeat(nationalId.length() - 4) + nationalId.substring(nationalId.length() - 4);
}
```

`**********4567` is enough for a customer to confirm the file is theirs and for compliance to
tell two records apart. The full number is in the database because the regulator requires it to
be retained, not because a screen needs to display it. Same instinct as never returning
`passwordHash` — the API returns what the reader needs, not what the table holds.

`national_id` is `UNIQUE`, so one id belongs to one holder. Trying to reuse one is a `409`, not
a silent second account.

---

## 6. Files

| File | Role |
|---|---|
| `KycProfile` | the entity that finally has a reason to exist; `@MapsId` shares its PK with `app_user` |
| `KycService` | submit, approve, read — the three verbs of the lifecycle |
| `KycLimits` | tier → daily ceiling, read from configuration |
| `KycProfileRepository` | includes `countAwaitingReview` for the review queue |
| `UserService` | registration, self-service and at the counter |
| `UserController` | `/api/users`, `/counter`, `/kyc`, `/kyc-review` |
| `WebUserController` | `/register`, `/users/new`, `/verify`, the review form |
| `V11__kyc_review_and_registrar.sql` | `registered_by`, `submitted_at`, `reviewed_by`, `review_note` |

The partial index is worth a look:

```sql
CREATE INDEX idx_kyc_profile_pending ON kyc_profile (submitted_at)
    WHERE verified_at IS NULL;
```

A review queue only ever asks for unreviewed files, and those are a shrinking minority of the
table. A partial index stores only the rows matching the `WHERE`, so it stays small no matter
how many verified customers accumulate behind it.

---

## 7. Not done

- **Rejection.** A review that can only say yes is not a review. A real queue needs
  *rejected, with a reason* and a way for the customer to resubmit.
- **Documents.** Real KYC uploads an id photo and a selfie, runs liveness detection, and OCRs
  the id. Here an employee types what they saw.
- **Screening.** Sanctions and politically-exposed-person lists are checked at onboarding and
  re-checked continuously; a hit routes to enhanced due diligence rather than to approval.
- **Re-KYC.** Verification expires. Nothing here ages a profile out.
- **Monthly and balance caps.** Only the daily ceiling is enforced; real tiers cap the balance
  a wallet may hold too.
