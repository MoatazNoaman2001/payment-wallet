# The Funding Module — Stripe, PayPal and Crypto

Money that comes from, or goes to, a system this wallet does not control.

The cash desk was already solved: a teller takes notes, and `CashService` posts a transfer
against a settlement account. A card payment is the same ledger operation with one difference
that changes everything — **the wallet does not decide when the money arrives.** Somebody else
does, minutes later, over a connection nobody is holding.

---

## 1. The one rule

> A deposit reaches the ledger when the provider says so, and never a moment earlier.

This is the same rule as *a customer may not deposit into their own account*, generalised. A
click on "Pay" is a request, not money. The customer coming back to your success page is not
money either — they can type that URL themselves. The only thing that counts is a **signed
webhook**, and that is the only place in this module that credits a wallet.

So a payment needs a record of its own, separate from `transfer`:

| | `transfer` | `payment_intent` |
|---|---|---|
| What it is | a fact about the ledger | a conversation with somebody else |
| When it exists | money has moved | money has been asked for |
| Can it fail? | no, it already happened | constantly |
| Immutable? | yes, append-only | no, it has a lifecycle |

```
REQUIRES_ACTION ──▶ PENDING ──▶ SUCCEEDED
       │                │
       │                └─────▶ FAILED / EXPIRED / CANCELLED
       └──────────────────────▶ CANCELLED
```

---

## 2. Deposit and withdrawal are not mirror images

**A deposit posts nothing until it settles.** There is no money yet; crediting on optimism is
how you give away goods for a payment that never clears.

**A withdrawal debits immediately**, before the provider is even asked:

```java
if (direction == PaymentDirection.WITHDRAWAL) {
    Transfer debit = post(account.getAccountNumber(), clearingAccount(provider, account), ...);
    intent.setTransfer(debit);
    intent.setStatus(PaymentIntentStatus.PENDING);
}
```

Money promised to somebody outside must stop being spendable inside *now*. Checking the balance
and then paying out is a race, not a check — two withdrawals submitted together would both see
enough money. Posting the debit reserves it, using the same locking the transfer engine already
does.

And when a payout fails days later, the money comes back as a **new compensating credit**, never
by editing the original entry:

```
REVERSAL   CREDIT   300.0  ->  800.0
WITHDRAWAL DEBIT    300.0  ->  500.0
TOPUP      CREDIT   800.0  ->  800.0
```

Read bottom-up, that is the whole story of a failed payout, and it is still true tomorrow. A
ledger you can edit is a ledger nobody can audit.

---

## 3. One clearing account per provider

`V12` adds `account.provider`. `NULL` is the cash drawer; a value names a payment provider:

```
SYSTEM-EGP             (provider NULL)   the counter
CLEARING-STRIPE-EGP    (provider STRIPE)
CLEARING-PAYPAL-EGP    (provider PAYPAL)
CLEARING-SANDBOX-EGP   (provider SANDBOX)
```

Money Stripe has collected is not money in the drawer. Stripe holds it for days, then pays out
in a lump sum, while the wallet owes the customer immediately. A per-provider clearing account
is what lets you ask *what does Stripe owe us today?* and compare that number to their
statement. One shared settlement account would answer only "what does the outside world owe us
in total", which reconciles against nothing.

The balance reads naturally: after collecting 1,050 in deposits,

```
 provider | clearing_balance
----------+------------------
 SANDBOX  |       -1050.0000
```

negative meaning *held outside the wallet, owed to us* — the same sign convention the cash
settlement account has always used.

> Watch out for the query this breaks. `findSystemAccountNumber(currency)` returned *the*
> SYSTEM account for a currency; with clearing accounts there are now five, and it needed
> `and a.provider is null` or it would fail with an incorrect-result-size error the moment a
> teller took a deposit. Adding rows to a table can break a query that never mentioned them.

---

## 4. Webhooks: from "at least once" to "effectively once"

Providers retry until they get a 2xx. The same event **will** arrive again — after a timeout,
after a deploy, after somebody replays it from a dashboard. Three independent guards:

**1. The signature.** An unauthenticated endpoint is unavoidable (Stripe holds no session), so
the signature *is* the authentication. Note the endpoint takes the body as a raw `String`:

```java
public Map<String, String> receive(@PathVariable PaymentProvider provider,
                                   @RequestBody String rawBody,
                                   @RequestHeader Map<String, String> headers)
```

Signatures are computed over the exact bytes sent. A round trip through a JSON parser and back
reorders keys and drops whitespace, which breaks the very signature that makes the request
trustworthy.

Stripe signs `timestamp + "." + body`, not the body alone, so a captured webhook cannot be
replayed next week — the timestamp is inside what was signed and is checked against the clock.
The comparison is constant-time, because one that returns early on the first wrong byte leaks
the correct signature one byte at a time.

**2. A unique event id.** Every delivery is recorded in `webhook_event` under
`UNIQUE (provider, provider_event_id)`. A replay finds the row and stops.

**3. The intent's own state.** Even an event that defeats both — a genuine duplicate under a
fresh id — hits `if (intent.getStatus().isTerminal()) return;` under a row lock.

A failure deliberately leaves `processed_at` NULL, so the provider's next retry tries again
rather than the error being swallowed.

---

## 5. The provider seam

```java
public interface PaymentProviderAdapter {
    PaymentProvider provider();
    boolean isConfigured();
    Set<PaymentDirection> supports();
    ProviderHandle createCharge(PaymentContext payment);
    ProviderHandle createPayout(PaymentContext payment);
    ProviderEvent readEvent(String rawBody, Map<String, String> headers);
}
```

Everything above this interface is identical for a card, a PayPal balance and a blockchain.
Everything provider-specific lives below it.

`isConfigured()` means a deployment with no Stripe key refuses Stripe *up front*:

```
422  STRIPE is not configured on this deployment
```

rather than failing somewhere deep in an HTTP call. `GET /api/funding/providers` reports the
same thing, so the UI only offers what actually works.

Note the adapters take a `PaymentContext` record, **not** the JPA entity. The provider call
happens outside any transaction, so an entity handed across that boundary is detached and every
lazy association on it is a `LazyInitializationException` waiting to happen. A record cannot
fail that way, and it also stops an adapter from quietly writing to the database.

### What each provider actually taught

| | The interesting part |
|---|---|
| **Stripe** | integer minor units against a `NUMERIC(19,4)` ledger; timestamped HMAC signatures |
| **PayPal** | approve and capture are two steps — approval moves no money; verification costs a round trip back to PayPal, because they publish no HMAC secret |
| **Crypto** | settlement is *gradual*; no chargebacks, but confirmations |
| **Sandbox** | the whole flow, offline, with no account anywhere |

Crypto is not a third card processor. The money is visible on-chain long before it is safe to
credit, so an event reporting fewer than the required confirmations changes nothing:

```java
case "charge:pending" -> confirmations >= requiredConfirmations
        ? PaymentIntentStatus.SUCCEEDED
        : null;   // seen, but not yet safe
```

Crediting on sight is how exchanges lose money to chain reorganisations.

### Money conversion refuses to round

```java
public static long toMinorUnits(BigDecimal amount, int minorUnits) {
    try {
        return amount.movePointRight(minorUnits).toBigIntegerExact().longValueExact();
    } catch (ArithmeticException ex) {
        throw new IllegalArgumentException(amount + " cannot be expressed exactly ...");
    }
}
```

This ledger keeps four decimal places; Stripe wants whole cents. `10.5050` has no exact
representation in cents, and a wallet that silently rounds is a wallet that does not balance.
Refusing loudly is the only safe answer.

---

## 6. Where the transactions are, and are not

`FundingService` carries no `@Transactional` at all. It talks to Stripe over the network, and a
database transaction held open across a third party's slow afternoon is a connection pool
waiting to run out. Each write is a separate short call into `FundingLedger`:

```
ledger.open(...)          tx 1   record the intent, reserve the money
adapter.createCharge(...)   —    the network call, no transaction
ledger.attach(...)        tx 2   store the provider's reference
```

The intent is committed **before** the provider is called, on purpose. If the call then fails,
`ledger.abandon(...)` marks it and returns any reserved money — but the record survives, which
is exactly what reconciliation needs when the provider might have accepted a payment whose
response never arrived.

### A bug worth keeping in the README

The idempotent-retry path originally did this:

```java
var existing = intentRepository.findByInitiatorAndKey(ownerPublicId, idempotencyKey);
if (existing.isPresent()) {
    return find(existing.get().getReference());   // <- same class
}
```

`find` is `@Transactional`. Spring's transaction management is a **proxy**, and a call from one
method of a bean to another goes straight down the inside of the object, past the proxy. No
transaction was started, the entity was detached, and reading its account threw
`LazyInitializationException`.

Thirteen tests covered this path and every one passed, because `@Transactional` on a test class
keeps a session open for the whole test and quietly makes lazy loading work. It only failed
against a running server. `FundingIdempotencyTest` is deliberately **not** transactional, and
fails immediately without the fix:

```
Tests run: 13, Failures: 0   FundingTest              (transactional — all green)
Tests run: 1,  Errors: 1     FundingIdempotencyTest   LazyInitializationException
```

The same trap sits behind `WebhookLog` being a separate bean from `WebhookService`:
`REQUIRES_NEW` on a method its own class calls does nothing at all.

---

## 7. Configuration

Keys go in `.env`, never in `application.yaml`:

```
STRIPE_SECRET_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...
PAYPAL_CLIENT_ID=...
PAYPAL_CLIENT_SECRET=...
PAYPAL_WEBHOOK_ID=...
CRYPTO_API_URL=...
CRYPTO_API_KEY=...
CRYPTO_WEBHOOK_SECRET=...
```

Add a key, restart, and the provider appears. Point each provider's dashboard at
`POST /api/webhooks/{STRIPE|PAYPAL|CRYPTO}`.

---

## 8. Honesty about what is tested

| | State |
|---|---|
| Intent lifecycle, ledger posting, reservation, compensation | tested end to end against the sandbox |
| Idempotency, replay, duplicate webhooks | tested |
| Stripe signature verification, tolerance, tampering | **tested** — it needs no key and no network |
| Crypto confirmation threshold | tested |
| Exact money conversion | tested |
| The outbound Stripe / PayPal / crypto HTTP calls | **written, never executed** |

That last row matters. The request shapes follow Stripe Checkout Sessions, PayPal Orders v2 and
a Coinbase-Commerce-shaped gateway, but nothing has run against a live account. Expect to fix
field names on the first real call. What *is* proven is everything on this side of the seam.

---

## 9. Not done

- **Reconciliation against provider statements.** The clearing accounts make it possible; a job
  that fetches Stripe's balance transactions and compares them is the other half.
- **A sweep for stale intents.** `findStale` exists and nothing calls it. An intent left
  `PENDING` because a webhook never arrived should be polled and resolved.
- **Refunds and chargebacks.** A card deposit can be pulled back months later; there is no
  `charge.dispute.created` handling here, and for a wallet that is a real hole.
- **Fees.** Providers take a cut. `transfer.fee` exists and is always zero.
- **Payout destinations.** A Stripe payout here goes to the platform's own bank account, not to
  the customer's — that needs Stripe Connect and a stored destination per customer.
- **Multi-currency.** A deposit must match the account's currency; there is no conversion.
