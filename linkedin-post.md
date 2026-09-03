# LinkedIn post — payment wallet, round 2

2968 characters. LinkedIn allows 3000, so there are 32 to spare if you want to reword
anything.

Copy everything between the lines below. It is plain text on purpose: LinkedIn does not
render markdown, so bold and bullets would post as literal asterisks.

---

Continuing the payment wallet from my last post. This round was mostly the UI and the rules around the money rather than the ledger.

The roles took the longest to get right. There are six: customer, teller, supervisor, compliance, auditor and admin. The idea I kept coming back to is that nobody should finish a whole transaction alone. A teller can take cash but cannot approve a customer's identity, compliance approves identity but has no access to the cash desk, and no employee can serve their own account. That last check is on the person, not the role, which is what makes it work.

KYC now blocks money instead of just sitting in a column. A new user is PENDING and cannot open an account, send or receive until compliance verifies them. Then the verification tier sets the daily limit: 5,000 for basic, 50,000 for verified, no limit for enhanced. How much you can move depends on how well the system knows you, the same reason your wallet app has limits.

For deposits I added Stripe, PayPal and a crypto gateway behind one interface, plus a sandbox provider so it runs offline. The main question was when money becomes real. The customer clicking pay is not enough, and neither is them landing on the success page, because anyone can type that URL. Only a signed webhook from the provider credits the account. Withdrawals work the other way: I take the money out of the wallet before calling the provider, because money promised to someone outside should not still be spendable inside. If the payout fails days later the money comes back as a new ledger entry, not by editing the old one.

Two bugs are worth mentioning. The first was in the payment retry path, which had 13 tests passing over it and was still broken. Spring handles transactions with a proxy, and I called the method from inside the same class, so the proxy never ran. The tests missed it because @Transactional on a test class keeps a database session open the whole time, which hides exactly that failure. It only broke against a real server. One test without a transaction catches it now.

The second one I liked more. A supervisor was allowed to use the cash desk, but no page linked them to it, so the only way in was typing the URL. Every authorization test passed, because authorization was correct. The word "staff" was just defined in three places and they had drifted apart. Now there is one definition, and a test that checks every role can reach whatever it is allowed to open.

What is not finished: Stripe, PayPal and the crypto adapter are written and unit tested, but none have run against a live account. Only the sandbox provider has gone end to end.

110 tests. Java 26, Spring Boot 4, PostgreSQL, Flyway, Thymeleaf.

One thing I still have not decided. With crypto a customer can send less than they were invoiced. I do not know whether to credit what arrived, refund it or hold it. If you have dealt with this I would like to hear how.

Video and repo in the comments.

---

## First comment

Put the links here rather than in the post itself, so the post is not down-ranked for
sending people off the platform.

---

Full walkthrough of every role in the app: [video link]

Code: github.com/MoatazNoaman2001/payment-wallet

Each module has its own README. The funding one covers why an approved PayPal order is not a paid one, and why every payment provider gets its own clearing account.

---

## Before posting

- Swap a few phrases for words you would actually use out loud.
- If you know how long this round took you, add it to the first paragraph. A specific
  number only you could know is the strongest signal a person wrote this.
- Your repo default branch is still dev, so a visitor lands there instead of main.
- The video is 12:52. Consider a 60 to 90 second cut for the post and keep the full one
  for the comment.
