# Plan: Transaction (`transaction`) and Payment (`payment`)

Status: **v2, 2026-11-24, waiting for approval.** v2 answers D6: several payment providers behind one port, ZaloPay as the first (section "Payment providers"). Decisions D1–D5 and D7–D14 still need the user's answer before Phase T0; no production code yet.

Sources (SOT = `docs-local/SRS_Hyperlocal_Marketplace_LaTeX/`; re-read the exact passages at the start of every phase):

| What | Where |
|---|---|
| Transaction description, `tab:sr-tx`, state machines `tab:sm-tx` and `tab:sm-appt`, FR-TX-01..15 | `tex/ch3_features.tex` lines 1995–2438 |
| Payment description, state machines (payment, payout), FR-PM-01..13 | `tex/ch3_features.tex` lines 2439–2818 |
| Sale marking (UC-LS-05, FR-LS-09), `tab:sm-listing` rows driven by transactions | `tex/ch3_features.tex` lines 883–1314; `tex/appC.tex` lines 223–236 |
| Full UC specs: UC-TX-01, 03, 08, 09; UC-PM-05; UC-LS-05. Others only in the UC index and the FRs | `tex/appC.tex` lines 45–110 (index), 223–360 |
| Data dictionary: `transactions`, `transaction_status_history`, `appointments`; `payout_accounts`, `payments`, `ledger_entries`, `refunds`, `payouts`, `payment_webhook_events` | `tex/ch4_dd.tex` lines 398–600 |
| Integrity, retention (payment records 10 years), `tab:params`, schema delta, OI-08 | `tex/ch4_tail.tex` lines 22, 40–55, 60–140, 175–200; `tex/appF.tex` OI-08 |
| Events and jobs (UC-SY-02, 03, 06, 13, 14), ADR-12, 14, 15 | `tex/appB.tex` lines 8–95 |
| Timers table and ledger example | `tex/appD.tex` lines 67–95 |
| Business rules BR-01, 04, 05, 08, 09, 10, 11, 12, 13, 14 | `tex/appG.tex` |
| Interfaces (gateway, VietQR, Idempotency-Key, webhook security), screens A19–A25 | `tex/ch5.tex` lines 53–59, 100–160 |
| NFR-27 payment correctness, NFR-32 webhook security, NFR-14 secrets | `tex/ch6.tex` |
| Access matrix rows for transactions, appointments, payments, payout accounts | `tex/ch3_tail.tex` |

## What this plan covers and how it is delivered

The SRS data dictionary has two modules, `trade` and `payment`, and the events table shows them talking to each other (payment consumes `TransactionAccepted`, `TransactionCompleted`, `TransactionCancelled`; trade consumes `PaymentSucceeded` and the others to show the payment state). The project's roadmap calls it one "Transaction + Payment" module. Two Java modules that listen to each other would be a cycle, so D1 decides the shape. Delivery is in two parts, each ending green, each asking before the next:

- **Part A, `transaction`** (FR-TX, UC-TX, UC-LS-05, UC-SY-02/03/06). No money. Phases T0–T6.
- **Part B, `payment`** (FR-PM, UC-PM, UC-SY-13). Phases P0–P6.

## Scope

### Functional requirements

| FR | Name | Priority / release | In this plan |
|---|---|---|---|
| FR-TX-01 | Offer to buy or ask for a free item | M / R1 | Part A. Fixed price, `Idempotency-Key`, `OfferCreated` |
| FR-TX-02 | Structured counter-offer | W / R2 | **No** (Won't) |
| FR-TX-03 | Respond: accept or reject | M / R1 | Part A. Accept reserves the listing, rejects the competing offers |
| FR-TX-04 | Withdraw an offer | M / R1 | Part A |
| FR-TX-05 | Reserve for a chat partner | S / R1 | Part A. `origin = RESERVATION` |
| FR-TX-06 | At most one ACCEPTED per listing | M / R1 | Part A. Partial unique indexes, 20-parallel-accepts test |
| FR-TX-07 | Appointments | S / R1 | Part A. Propose, confirm, decline, reschedule, cancel, 60-minute reminder |
| FR-TX-08 | Complete | M / R1 | Part A. Seller hands over, buyer receives, or 72 h silence |
| FR-TX-09 | Problem with the item | S / R1 | **Hook only** (TX-W1): `on_hold` and a facade; the dispute itself is Moderation (UC-SP-02) |
| FR-TX-10 | Cancel an ACCEPTED transaction | M / R1 | Part A. Reason codes, late-cancellation flag, refund request event |
| FR-TX-11 | No-show | S / R1 | Part A, up to the 48 h contest; the dispute on contest is Moderation (TX-W3) |
| FR-TX-12 | Expiry | M / R1 | Part A. Job UC-SY-02 every 5 minutes |
| FR-TX-13 | Single transition point | M / R1 | Part A. Version, `Idempotency-Key`, history row, outbox event in one commit |
| FR-TX-14 | Buy and sell history | M / R1 | Part A. List and detail |
| FR-TX-15 | Sanction effects | M / R1 | Part A. Listener on `UserSanctioned` |
| FR-LS-09 (a) | Mark sold to a chat partner, `SaleMarked`, `SaleDenied` (UC-LS-05, UC-TX-08 A6) | M / R1 | Part A. Closes LS-W4 |
| FR-PM-01 | Choose a payment method | M (CASH) / S | Part B |
| FR-PM-02 | Payout account | S / R1 | Part B. AES-256-GCM, masked, 24 h lock after a change |
| FR-PM-03 | VietQR transfer | S / R1 | Part B. Local EMVCo QR, seller confirms receipt |
| FR-PM-04 | Safe Payment (escrow) | S / R1 | Part B. Order at the gateway, price + 2 %, 15-minute link |
| FR-PM-05 | IPN callback | S / R1 | Part B. HMAC, 5-minute replay window, dedup by gateway transaction id |
| FR-PM-06 | Double-entry ledger | S / R1 | Part B. Append-only, balanced |
| FR-PM-07 | Release and payout | S / R1 | Part B. Gateway disbursement or manual batch |
| FR-PM-08 | Refund | S / R1 | Part B. Full refund incl. fee, retries with back-off |
| FR-PM-09 | No release during a dispute | S / R1 | Part B. Reads `on_hold` from Transaction |
| FR-PM-10 | Payment history and receipts | S / R1 | Part B |
| FR-PM-11 | Daily reconciliation | C / R2 | **No** |
| FR-PM-12 | Limits (10 000 to 50 000 000 VND), no card data | S / R1 | Part B |
| FR-PM-13 | Tax withholding | W / R2 | **No** (Won't); ledger and payout leave room |

### Use cases, screens, events, jobs

- **UC:** TX-01, 03, 04, 05, 06, 07, 08, 09, 10, 11 (TX-02 Won't); LS-05; PM-01..07; SY-02, SY-03, SY-06 (every 5 minutes), SY-13 (on `TransactionCompleted`). UC-AD-04 is Moderation's; Transaction only reacts to `UserSanctioned`.
- **Screens (Android, for the contract):** A19 appointments, A20 offer sheet, A21 transaction list, A22 transaction detail, A23 payment, A24 payout account, A25 payment history.
- **Events produced by Transaction:** `OfferCreated`, `OfferRejected`, `OfferWithdrawn`, `OfferExpired`, `SaleMarked`, `SaleDenied`, `TransactionAccepted`, `AppointmentProposed`, `AppointmentConfirmed`, `AppointmentCancelled`, `HandoverConfirmed`, `ReceiptConfirmed`, `TransactionCompleted`, `TransactionCancelled`, `TransactionExpired`, `NoShowReported`, `NoShowConfirmed`, `LateCancellationRecorded`.
- **Events produced by Payment:** `PaymentSucceeded`, `PaymentFailed`, `PaymentExpired`, `PaymentReleased`, `RefundCompleted`, `RefundFailed`, `PayoutPaid`, `PayoutFailed`.
- **Events consumed:** Transaction: `ListingStatusChanged` (reject open offers on SOLD or REMOVED, LS-W5), `UserSanctioned` (FR-TX-15). Payment: `TransactionAccepted` (offer the methods), `TransactionCompleted` (release), `TransactionCancelled`, `TransactionExpired` (void or refund), `UserSanctioned` through the cancellation it causes. Consumed later: `DisputeOpened` and `DisputeResolved` (Moderation, through the hold facade, TX-W1, TX-W2).
- **Jobs:** UC-SY-02 expire offers and transactions (5 min), UC-SY-03 auto-complete (5 min), UC-SY-06 appointment reminder (5 min), no-show confirmation after 48 h (5 min, with UC-SY-03), payment link expiry and the status query after 5 minutes without IPN (every minute), refund retry (every minute), payout run (every 5 minutes). All `@Transactional run()`, safe to run twice, and ShedLock waits for X-W2.

### Tables

V4 created an early `transactions` table with the v0.1 shape (no WITHDRAWN, no `origin`, no confirmation or payment columns, still `exchange_note`; OI-08). It holds no meaningful rows. **V17 drops and recreates it** (the V12, V14 and V15 approach) with `transaction_status_history` and `appointments`. **V18** creates the six payment tables.

V16.1 adds `transactions_accepted_listing_uq` to the current schema for W07-C. Keep the same rule when V17 recreates the table. `TransactionReservationIntegrationTests` verifies concurrent acceptance and release after cancellation at the database level.

| Table | Notes |
|---|---|
| `transactions` | 28 columns of the dictionary. `origin` OFFER, RESERVATION, MARK_SOLD. `status` OFFERED, ACCEPTED, COMPLETED, REJECTED, WITHDRAWN, EXPIRED, CANCELLED. `current_price` fixed for the life of the offer. `payment_method` CASH default; `payment_status` NONE default. `version` for the optimistic lock. Partial unique index (`listing_id`) WHERE ACCEPTED; partial unique index (`listing_id`, `buyer_id`) WHERE OFFERED or ACCEPTED (`transactions_open_offer_uq`, kept from V4); index (`status`, `expires_at`) for the jobs; indexes by buyer and by seller for the history |
| `transaction_status_history` | Append-only: from, to, action (CHECK list), price, actor, actor type, reason, `idempotency_key` |
| `appointments` | `geography(Point,4326)` place location, status machine of `tab:sm-appt`, `reminder_sent_at`, no-show columns with CHECK `noshow_reported_at >= meet_at + 30 min` |
| `payout_accounts` | `account_number_enc` bytea, `account_last4`, `payout_locked_until` |
| `payments` | `total_amount` generated (amount + fee); UK `gateway_order_id`, `gateway_txn_id`; statuses of both methods |
| `ledger_entries` | Append-only; `journal_id` balance; triggers refuse UPDATE and DELETE (D8) |
| `refunds`, `payouts`, `payment_webhook_events` | As in the dictionary; UK on `payouts.payment_id` (one payout per payment) |

### Parameters (`tab:params`, verbatim)

`offer.ttl_hours` = 48 (already seeded in V8), `trade.sale_deny_hours` = 72, `trade.accepted_ttl_days` = 7, `trade.auto_confirm_hours` = 72, `trade.late_cancel_hours` = 2, `trade.noshow_contest_hours` = 48, `appointment.reminder_minutes` = 60, `appointment.noshow_grace_minutes` = 30, `payment.safe_fee_rate` = 0.02, `payment.safe_min_vnd` = 10000, `payment.safe_max_vnd` = 50000000, `payment.link_ttl_minutes` = 15, `payment.payout_lock_hours_after_change` = 24, `payment.webhook_retention_months` = 24, `payment.record_retention_years` = 10 (waiting for confirmation, LG-06). Reused: `app.idempotency.ttl`. **Check V8 and V9 first**: seed only the keys that are missing.

### Error codes (from the FR exception rows and the UC exception flows)

| Code | HTTP | Where |
|---|---|---|
| `LISTING_NOT_AVAILABLE` | 409 | Offer on a listing that is not ACTIVE (FR-TX-01, UC-TX-01 E5) |
| `SELF_ACTION_NOT_ALLOWED` | 422 | Own listing (FR-TX-01 says "403/422"; D13). Exists |
| `OFFER_ALREADY_OPEN` | 409 | The buyer has an OFFERED or ACCEPTED transaction on the listing, with a link to it |
| `INVALID_OFFER_PRICE` | 422 | Price different from the list price when `accept_offers = false`; not 0 for a free item; negative |
| `LISTING_AREA_EXPIRED` | 409 | The listing's area expired (FR-TX-01) |
| `TRANSACTION_NOT_OFFERED` | 409 | Respond or withdraw when no longer OFFERED |
| `TRANSACTION_ALREADY_ACCEPTED` | 409 | Withdraw an ACCEPTED offer |
| `LISTING_ALREADY_RESERVED` | 409 | A second accept, reservation or sale marking (BR-04) |
| `TRANSACTION_NOT_ACCEPTED` | 409 | Confirm, cancel or schedule when not ACCEPTED |
| `CONFIRMATION_RECORDED` | 409 | Cancel after a confirmation (use a dispute) |
| `DISPUTE_OPEN` | 409 | Confirm or cancel while a dispute holds the transaction |
| `SALE_DENY_WINDOW_CLOSED` | 409 | Deny a marked sale after 72 h |
| `COUNTERPART_UNAVAILABLE` | 409 | The chosen buyer is locked (UC-LS-05 E4) |
| `NOT_A_CHAT_PARTNER` | 422 | Mark sold or reserve for someone without a conversation |
| `VERSION_CONFLICT` | 409 | Stale `version` (D3) |
| `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `DUPLICATE_REQUEST` | 400, 409 | Exist |
| `SAFE_PAYMENT_LIMIT` | 422 | Safe Payment outside 10 000 to 50 000 000 |
| `PAYMENT_ALREADY_INITIATED` | 409 | A payment PENDING or HELD exists; or a method change after initiation |
| `GATEWAY_UNAVAILABLE` | 502/503 | Gateway error or timeout |
| `PAYMENT_ALREADY_RELEASED` | 409 | A refund after release |
| `INVALID_BANK`, `INVALID_ACCOUNT_NUMBER` | 400 | Payout account (FR-PM-02 "400"; names chosen, D13) |
| `REAUTHENTICATION_FAILED` | 401/403 | Wrong password when saving the payout account (code reused from identity if it exists) |
| `PAYMENT_NOT_FOUND`, `TRANSACTION_NOT_FOUND` | 404 | Someone else's resource is 404 (FR-ID-18). Exist or new |
| Existing | | `TRADING_GATE_FAILED`, `OUT_OF_RANGE`, `CONTACT_NOT_ALLOWED`, `INVALID_LISTING_STATE`, `LISTING_NOT_FOUND`, `VALIDATION_FAILED`, `INVALID_CURSOR` |

## Current state and gaps

- **Exists (upstream hooks waiting for this module):** `listing.ListingReservations` (`reserve`, `release`, `completeReservedSale`, `markSoldToChatPartner`, `revertDeniedSale`), SPIs `OpenTransactionCheck`, `SellerTradeHistory`, `OpenOfferCounts` (listing), `AreaRemovalBlocker` (area), `AccountDeletionBlocker` (identity), `ListingStatusChanged`, `UserSanctioned`, `TradingGate`, `InteractionRange`, `UserBlocks`, `AccountStanding`, `@Idempotent`, `RateLimiter`, `CursorPage`, `ImageStorage`. Chat is ready for this module: `ConversationService.openForTrade`, `isChatPartner`, `SystemMessagePoster`, `NotificationService` with the reserved `NotificationType` entries, `SourceEventId`.
- **Stale:** V4 `transactions` (OI-08). `transaction/package-info.java` allows only shared, identity, listing.
- **Missing:** everything else: the six payment tables, `appointments`, `transaction_status_history`, the gateway port and adapter, the VietQR encoder, AES key handling, the ledger, all jobs and endpoints. `reputation/package-info.java` exists and is empty.
- **Error codes already in `ErrorCodes`:** `AREA_HAS_OPEN_TRANSACTIONS`, `LISTING_HAS_OPEN_TRANSACTIONS`, `SELF_ACTION_NOT_ALLOWED`, and the idempotency codes.

## Decisions to confirm

| # | Question | Recommendation | Why |
|---|---|---|---|
| D1 | Module layout | Two Modulith modules: `transaction` (allowed: shared, identity, listing, areaverification) and `payment` (allowed: shared, identity, transaction). `chat` also allows `payment`. `transaction` never depends on `payment`: payment pushes the state into `transaction.TransactionPaymentStatus.record(...)` (facade) and transaction keeps its own `payment_status` copy; payment reads prices and parties through `transaction.TransactionLookup` and learns of `on_hold` the same way | The SRS dictionary has both modules and their events cross. A listener is a module dependency, so both directions would be a cycle. The dictionary's `transactions.payment_status` ("bản sao ... cập nhật từ sự kiện payment") is exactly this copy |
| D2 | Order and size of delivery | Part A first (about 14 endpoints, 3 jobs, 5 SPIs), then Part B (about 11 endpoints, gateway, ledger), each with its own approval | Part A has no money and unblocks Reputation, Chat and Listing rows; Part B carries the correctness and security risk (NFR-27, NFR-32) and needs your gateway account |
| D3 | Error code of a stale version | `VERSION_CONFLICT` (409) for transactions, appointments and payments, as the SRS says in FR-TX-13 and in four UC exception flows. Listing keeps `CONCURRENT_MODIFICATION` (DEC-LS-5, accepted) | Codes are taken verbatim from the SRS. Two codes for the same idea is a cost for the Android app; if you prefer one, say so and I use `CONCURRENT_MODIFICATION` here too and note the departure from the SRS |
| D4 | One transition point | Domain class `Transaction` with `TransactionAction` and a transition table (the rows of `tab:sm-tx`), and one application service `TransactionTransitions` that every command, job and listener calls: loads with `FOR UPDATE` or the version, applies the action, writes the history row and publishes the event | FR-TX-13 acceptance: an interrupted transition leaves neither a state change without history nor an event without a state change |
| D5 | Disputes (FR-TX-09, FR-TX-11 contest, FR-PM-09) | Moderation owns the `disputes` table and the endpoints. Transaction provides `TransactionHold.hold(transactionId, reason)` and `resume(transactionId, outcome)` and the `on_hold`, `hold_started_at` columns; deadlines move by the paused time. The "Có vấn đề với món hàng" button shows disabled with a reason until Moderation exists | The SRS dictionary puts disputes in the moderation module. Building a fake dispute here would be thrown away |
| D6 (answered) | Payment gateway | **Several providers behind one port; ZaloPay is the first adapter** (user, 2026-11-24). Design in the next section: `PaymentGateway` per provider, a registry, the provider stored on each payment, the webhook path per provider, settings per provider. `SandboxPaymentGateway` stays as the local and test provider | The SRS asks for a neutral gateway (ADR-15); `payments.provider` and `/payments/webhooks/{provider}` already assume it |
| D7 | Bank list for VietQR | A classpath resource `banks.json` (name, short name, NAPAS BIN) loaded at startup; not a table. I will fill it from the public NAPAS list and mark in the plan that the BINs need a check against the current list before release | The dictionary has no bank table; the SRS says "seeded", and a resource is simplest to change |
| D8 | Ledger immutability and balance | Database triggers: `BEFORE UPDATE OR DELETE` on `ledger_entries` raises an error (the application role has no way around it in one database), and a deferred constraint trigger checks that the debits and credits of each `journal_id` are equal at commit; the application also checks before commit | FR-PM-06 acceptance: UPDATE and DELETE are refused and every journal balances at any moment |
| D9 | Account number encryption | AES-256-GCM with a random 96-bit IV stored in front of the cipher text (`iv ‖ ciphertext`), key from `PAYMENT_AES_KEY` (base64, 32 bytes). No key rotation in R1 (noted). The number is never in a log, response or export | FR-PM-02, NFR-14. New secret: manual setup and test (skill step 14) |
| D10 | Money | `bigint` VND everywhere; fee = `round(amount × 0.02)` half up with `BigDecimal`; 500 000 gives 510 000 | FR-PM-04 acceptance |
| D11 | Where the sale marking endpoint lives | `POST /transactions/mark-sold` in Transaction (`{listingId, buyerId, price, listingVersion}`, `Idempotency-Key`); listing keeps `sold-outside` | DEC-LS-3: "sold outside the app" is Listing, "sold to a chat partner" is Transaction |
| D12 | Chat and notification producers | Written in this plan's Phase T5 inside `chat` (it already depends on `transaction`, and now on `payment`): system messages (CH-W1), notifications (NT-W1, NT-W2), `ChatPartners` SPI implementation (CH-W4), `openForTrade` (CH-W5) | Chat owns its own messages and notifications; Transaction only publishes events |
| D13 | Names where the SRS gives no code | Self-offer: 422 `SELF_ACTION_NOT_ALLOWED` (the FR says "403/422"; 422 as in chat). Payout account: 400 `INVALID_BANK`, `INVALID_ACCOUNT_NUMBER`. Re-authentication: the existing identity code | The FR rows say only "400" |
| D14 | Payout channel in R1 | Rows get `channel = MANUAL_BATCH` by default and `GATEWAY_DISBURSEMENT` only when the adapter supports it; Finance marks batches paid through the Admin Console (not built yet, PM-W2). Until then payouts wait in PENDING or PROCESSING and the ledger shows SELLER_PAYABLE | FR-PM-07 allows both; the console is a later module |

Readings of the SRS taken without a question (tell the user, record in the status file):

- **R1 — Free items.** BR-09: price 0 is "cho tặng", so several buyers may hold OFFERED at the same time (the partial unique index is per buyer); the giver accepts one; no payment.
- **R2 — MARK_SOLD.** A transaction created by marking a sale starts ACCEPTED with `seller_confirmed_at` already set, the listing becomes SOLD at once (`markSoldToChatPartner`), `sale_marked_at` opens the 72 h window; the buyer's silence completes it with `auto_confirmed = true`, a denial cancels with `BUYER_DENIED` and the listing returns to ACTIVE (`revertDeniedSale`) without a penalty.
- **R3 — Expiry after a confirmation.** The 7-day expiry applies only while no party has confirmed; once one confirms, `auto_complete_at` (+72 h) governs and the job ignores `expires_at`.
- **R4 — Hold.** While `on_hold` is true all timers stop; on resume every deadline moves by the paused time (appendix D).
- **R5 — BUYER_DENIED** is set only by the system when the buyer denies a marked sale; the cancel endpoint refuses it.
- **R6 — Late cancellation.** `late_cancellation = true` when the cancel is less than `trade.late_cancel_hours` before a CONFIRMED appointment; the penalty (−2) is Reputation's after the 7-day contest window (BR-13); Transaction only publishes `LateCancellationRecorded`.
- **R7 — LISTING_NOT_AVAILABLE and INVALID_LISTING_STATE.** The SRS uses the first in UC-TX-01 E5 and the second in UC-LS-05 E2; each is used where its use case says.
- **R8 — Method change.** Changing the method voids a QR_ISSUED payment; after a Safe Payment is PENDING or HELD it is 409 `PAYMENT_ALREADY_INITIATED`.
- **R9 — Webhook endpoint.** `POST /api/v1/payments/webhooks/{provider}` is public (no JWT), protected by HMAC, the 5-minute window and an optional IP allow-list property; the answer follows the provider's contract.

## Payment providers: several behind one port, ZaloPay first (decision D6)

"Multi payment" is read as **several payment providers** (ZaloPay now; MoMo, VNPay or others later) selected per payment, not several merchants with their own credentials. If you meant the second (one app with several merchant accounts), tell me before Phase P0, because it changes the settings and the `payments` table.

### Design

| Piece | What it does |
|---|---|
| `PaymentGateway` (port, one implementation per provider) | `provider()` code; `createOrder(order)` returns the payment URL and the provider order references; `queryOrder(ref)`; `refund(request)` and `queryRefund(ref)`; `verifyCallback(rawBody, headers)` returns a parsed `GatewayEvent` or a refusal; `callbackReply(outcome)` returns the body the provider expects; `capabilities()` (partial refund, disbursement, order lifetime limits) |
| `PaymentGatewayRegistry` | Finds the adapter by provider code; only providers listed in `app.payment.enabled-providers` exist. Unknown or disabled provider: 404 on the webhook, `GATEWAY_UNAVAILABLE` on a new payment |
| `payments.provider` | The provider of each payment, set when it is created and never changed; refunds, status queries and callbacks always use the payment's own provider |
| Choosing the provider | `app.payment.default-provider` for Safe Payment in R1; the payment options already return `providers: [...]` so the app can later let the buyer pick. A provider that is down is skipped and its option is shown disabled with a reason |
| Settings | `app.payment.providers.<code>.*` (for ZaloPay `app-id`, `key1`, `key2`, `base-url`, `callback-url`, `redirect-url`, `timeout`) with the secrets from environment variables; no secret in the repository (NFR-14) |
| Webhook | `POST /api/v1/payments/webhooks/{provider}`: the same controller for every provider; it stores the raw body and headers in `payment_webhook_events` first, then asks the adapter to verify and parse |
| Adding a provider later | One adapter class, its settings, its entry in `enabled-providers`, a contract test that every adapter passes (`PaymentGatewayContractTests`: forged, modified and replayed callbacks refused; the same event twice applies once), and a section in `docs/setup-local.md` |
| `SandboxPaymentGateway` | In-process provider `SANDBOX`: signs and verifies like a real one and lets tests and local runs simulate a payment, a failure, an IPN and a refund; never enabled in production |

### ZaloPay (Phase 1 provider): what the official pages say

Read on 2026-11-24 from `docs.zalopay.vn` (create order, query order, refund, query refund, callback, secure data transmission, status codes, test instructions). **Not confirmed on those pages, so confirmed in the API Explorer and with ZaloPay before P6:** the sandbox and production host names and the endpoint paths, the sandbox AppId, key1 and key2 (ZaloPay gives them on request), any callback IP list, and the retry schedule (the pages say only "up to 3 times").

| Step | ZaloPay |
|---|---|
| Keys | `key1` signs what we send; `key2` verifies what ZaloPay sends. HMAC-SHA256, hex |
| Create order | `POST` JSON or form: `app_id`, `app_user` (the payer id, at most 50), `app_trans_id` (**`yymmdd_` in Vietnam time + our short payment id, at most 40, unique**; a repeat is sub code -68), `app_time` (Unix ms), `amount` (integer VND = `payments.total_amount`), `description`, `item` (`"[]"`), `embed_data` (JSON string with `redirecturl` for the app's deep link and optionally `preferred_payment_method`), `expire_duration_seconds` (**we send 900** from `payment.link_ttl_minutes`; ZaloPay accepts 300 to 2 592 000), `callback_url`, `mac` over `app_id|app_trans_id|app_user|amount|app_time|embed_data|item` with key1 |
| Create answer | `return_code` 1 success, 2 failure (see `sub_return_code`), 3 processing; `order_url` becomes `payments.payment_url`; `zp_trans_token` and `order_token` are kept in the order record |
| Callback (IPN) | `POST` JSON `{data, mac, type}`; `mac` = HMAC-SHA256 of the **raw `data` string as received** with key2; `data` has `app_trans_id`, `zp_trans_id`, `amount`, `server_time`, `channel` and more. We store the raw body first, verify, then apply. `gateway_txn_id` = `zp_trans_id` (the dedup key), `gateway_order_id` = `app_trans_id`. The answer is `{"return_code":1,"return_message":"success"}` **only after** the payment was applied; a bad MAC gets `-1` and changes nothing; a processing error gets `0` so ZaloPay calls again |
| Replay window (NFR-32) | `server_time` of the callback must be within 5 minutes of our clock; a late retry of an already applied event is answered 1 without any change (the dedup key decides) |
| Status query | `POST` `app_id`, `app_trans_id`, `mac` over `app_id|app_trans_id|key1`; `return_code` 1 paid, 3 processing (`is_processing`), 2 failed, with sub codes such as -54 expired and -101 order not found; used 5 minutes after the buyer returns without an IPN (FR-PM-05) and once more after the 15-minute lifetime |
| Refund | `POST` `app_id`, `m_refund_id` (**`yymmdd_<app_id>_<our refund id>`, unique per refund**), `zp_trans_id`, `amount` (full: price + fee, FR-PM-08), `description`, `timestamp` (Unix ms, accepted for 15 minutes), `mac` over `app_id|zp_trans_id|amount|description|timestamp` with key1. The answer only says the request was received; the result comes from the refund query, so `REFUND_PENDING` stays until it is confirmed |
| Refund query | `POST` `app_id`, `m_refund_id`, `timestamp`, `mac` over `app_id|m_refund_id|timestamp` with key1; -1 and -16 mean still in progress, 1 success (inferred), anything else counts as a failed attempt for the back-off |
| Money in and out | Amount is an integer in VND. Partial refunds are not supported for some non-ZaloPay wallets (-32); we always refund in full, so this does not matter |
| Payout (disbursement) | No payout API appears on the pages read, so for ZaloPay `payouts.channel = MANUAL_BATCH` (D14): Finance pays the seller by bank transfer and marks the batch paid |
| Sandbox testing | Test wallet app with code 111111; ATM and card numbers on `docs.zalopay.vn/docs/developer-tools/test-instructions`; callback logs at `sbmc.zalopay.vn/devtool`. ZaloPay must reach our callback URL over HTTPS, so local runs need a public tunnel |

### What I need from you for ZaloPay (before Phase P6, not before Part A)

1. Request the **sandbox AppId, key1 and key2** from ZaloPay (their integration guide says to send a phone number and email to ZaloPay BD) and tell me when you have them; I never need to see the keys, they go in `.env` (`ZALOPAY_APP_ID`, `ZALOPAY_KEY1`, `ZALOPAY_KEY2`).
2. A way to expose the local backend over HTTPS (a tunnel such as ngrok or cloudflared) and the URL registered as the callback URL in the ZaloPay sandbox merchant page.
3. The deep link the Android app opens after payment (`redirecturl`).

Until then everything is built and tested with `SandboxPaymentGateway`, and the ZaloPay adapter is tested against recorded request and response examples of the official pages (signature vectors included) with a stub server, the way `FcmPushGatewayTests` does it.

## Waits for other modules

### Rows this plan opens (copy to the register as **Planned**)

| ID | Waiting feature (SRS) | Waits for | Stand-in now | Hook built now | Closed by |
|---|---|---|---|---|---|
| TX-W1 | FR-TX-09: report a problem with the item opens a dispute | Moderation (UC-SP-02, FR-MD) | Button disabled with a reason; no dispute | Facade `transaction.TransactionHold` (`hold`, `resume`), columns `on_hold`, `hold_started_at` | Moderation |
| TX-W2 | `DisputeResolved` outcomes: resume, cancel and refund, or release | Moderation | Nothing resumes | `TransactionHold.resume(transactionId, outcome)`, events `TransactionCancelled`/`TransactionCompleted` as usual, `payment.RefundRequests` (PM-W1) | Moderation |
| TX-W3 | FR-TX-11: a contested no-show opens a NO_SHOW dispute | Moderation | The contest is recorded (`contested_at`, appointment DISPUTED) and the transaction is put on hold; no penalty | `TransactionHold`, `appointments.contested_at` | Moderation |
| TX-W4 | `LateCancellationRecorded`, `NoShowConfirmed` penalties (−2) after the contest window (BR-13) | Reputation (FR-RP-09) | Events published, no score change | The events | Reputation |
| TX-W5 | `TransactionCompleted` opens the 14-day review window; completed-transaction count on profiles (ID-W2) | Reputation | No review invitation | The event and `TransactionLookup` | Reputation |
| PM-W1 | Refund when a dispute is upheld (FR-PM-08, `DISPUTE_UPHELD`); `PAYMENT_ALREADY_RELEASED` for Moderation (FR-MD-07) | Moderation | No dispute refund | Facade `payment.RefundRequests.request(transactionId, reason, requestedBy)` | Moderation |
| PM-W2 | Finance Operator console W08: list payments, retry or approve refunds, manage payout batches, mark manual payouts paid, CSV export (FR-AD-09); W09 tasks | Admin Console | Payouts and failed refunds wait | Facade `payment.FinanceOperations` (list, retry refund, mark batch paid, export rows) with the audit hook | Admin Console |
| PM-W3 | FR-PM-11 daily reconciliation (UC-SY-14, R2); FR-PM-13 tax (Won't) | R2 | Not built | Ledger and payout keep room for a withholding account | R2 |
| PM-W4 | ZaloPay credentials from ZaloPay (sandbox AppId, key1, key2, registered callback URL); further providers; gateway disbursement (not in the ZaloPay pages read, so payouts are MANUAL_BATCH, D14) | You (ZaloPay) | `SandboxPaymentGateway` | Port `PaymentGateway`, registry | Payment Phase P6 (ZaloPay), later work (other providers) |

### Register rows this plan closes

| Row | How | Phase |
|---|---|---|
| ID-W3 (account deletion blocked by open obligations) | `AccountDeletionBlocker` in Transaction (OFFERED, ACCEPTED, unconfirmed) and in Payment (HELD, pending refund or payout) | T3, P3 |
| AR-W1 (area removal blocked) | `AreaRemovalBlocker` in Transaction | T3 |
| LS-W1 (`SellerTradeHistory`) | Implemented in Transaction | T3 |
| LS-W2 (`OpenTransactionCheck`) | Implemented in Transaction | T3 |
| LS-W3 (`ListingReservations` reserve, release, sold) | Called from the transitions | T2 |
| LS-W4 (sold to a chat partner, `SaleMarked`/`SaleDenied`) | `POST /transactions/mark-sold` and the deny | T2, T4 |
| LS-W5 (reject open offers on SOLD or REMOVED) | Listener on `ListingStatusChanged` | T2 |
| LS-W6, offer part (`OpenOfferCounts`) | Implemented in Transaction | T3 |
| LS-W8, Offer button | `POST /transactions` behind the Offer button; the listing detail already returns the state | T4 |
| CH-W1, CH-W4, CH-W5, NT-W1 (trade part), NT-W2 | Listeners and the SPI implementation inside `chat` | T5, P5 |
| ID-W2, completed-transaction count part | `TransactionLookup.completedCount(userId)` available; Reputation closes the rest | T3 |

## Phases

Each phase re-reads its SRS passages first, ends with `./gradlew build` green, runs the **dependency gate** (skill step 13) and, in Part B, the **manual setup and test** (skill step 14), and updates `01-project-status.md` (FR status **and the register**). No commits; ask the user before each next phase.

### Part A: `transaction`

#### T0. Groundwork

V17 (`transaction_v2`: drop the V4 table, create `transactions`, `transaction_status_history`, `appointments`, the partial indexes, seed the missing parameters); error codes; `ApiPaths` (`/transactions`, `/appointments`); `package-info` of `transaction` (add `areaverification`) and `chat` (add `payment` when Part B starts); `ListingLookup` already gives status, seller, price and `accept_offers` (check that `ListingSnapshot` has the list price and `accept_offers`; it has both); schema test for the CHECKs, the two partial indexes and the 20-parallel-accepts constraint.

#### T1. Domain (pure Java, test-first)

| Class | Responsibility | Key tests (SRS numbers) |
|---|---|---|
| `TransactionStatus`, `TransactionAction` | The 7 statuses and the actions of `tab:sm-tx` | Every row of the table is an allowed edge; every edge out of a final status (COMPLETED, REJECTED, WITHDRAWN, EXPIRED, CANCELLED) is refused with 409 |
| `Transaction` | Entity with `version`, `origin`, confirmation timestamps, hold, deadlines; one method per action returning the history entry | Offer at 48 h exactly and 48 h + 1 s; accept sets `accepted_at` and `expires_at` + 7 days; MARK_SOLD starts ACCEPTED with `seller_confirmed_at`; confirmations idempotent |
| `OfferPrice` | FR-TX-01 price rules | Free item: 0 only; `accept_offers = false`: equals the list price; negative and over-long note (300) refused |
| `Deadlines` | `offer.ttl_hours`, `trade.accepted_ttl_days`, `trade.auto_confirm_hours`, `trade.sale_deny_hours`, hold shift | 72 h and 72 h + 5 min (FR-TX-08); deny window 72 h; paused 10 h moves every deadline by 10 h; no expiry after one confirmation (R3) |
| `CancelReason`, `CancellationPolicy` | Reason codes; `BUYER_DENIED` system only; late cancellation | 2 h before a CONFIRMED appointment is late, 2 h 1 min is not; a cancel after a confirmation is `CONFIRMATION_RECORDED` |
| `Appointment`, `AppointmentStatus` | `tab:sm-appt`, supersede on reschedule, past time refused | Every edge; reminder only for CONFIRMED and once |
| `NoShowRules` | 30 min grace, 48 h contest | Report at 29 min refused, 30 min accepted; contest at 48 h; no contest confirms |
| `TransactionParticipants` | Roles and the "is a party" check | A third user is 404 |

#### T2. Application (one block per UC: precondition, guards, behaviour, output, events)

- **UC-TX-01 offer:** gate, range (`allowsNewContacts` for the listing's area, else `LISTING_AREA_EXPIRED`), block, own listing, listing ACTIVE, open offer, price; insert under the unique index; create the conversation and post the system message through chat (T5); events `OfferCreated`. Replay with the same `Idempotency-Key` returns the same transaction.
- **UC-TX-03 respond:** accept (lock, `ListingReservations.reserve`, reject the other OFFERED with `OfferRejected`, `TransactionAccepted`) or reject; two accepts at once give one winner and `LISTING_ALREADY_RESERVED`.
- **UC-TX-04 withdraw**, **UC-TX-05 reserve** (chat partner required, `origin = RESERVATION`), **UC-LS-05 mark sold** (branch A with an ACCEPTED transaction; branch B for a chat partner; sold outside stays in Listing), **deny a marked sale** within 72 h.
- **UC-TX-06 appointments:** propose, confirm, decline, reschedule (supersede), cancel; `AppointmentProposed`, `AppointmentConfirmed`, `AppointmentCancelled`.
- **UC-TX-07/08 confirm handover and receipt:** both confirmations or one plus 72 h; idempotent; `HandoverConfirmed`, `ReceiptConfirmed`, `TransactionCompleted` once; listing SOLD; appointment DONE; `DISPUTE_OPEN` while on hold.
- **UC-TX-09 cancel:** reason, version, listing back to ACTIVE, appointment CANCELLED, `LateCancellationRecorded` when late, `TransactionCancelled` (Payment refunds from it).
- **UC-TX-10 no-show:** report from 30 min after a CONFIRMED appointment, contest within 48 h (hold, appointment DISPUTED), confirmation job and `NoShowConfirmed`.
- **UC-TX-11 history:** list by role and status with a cursor; detail with timeline, appointment, payment status.
- **Jobs:** UC-SY-02 (OFFERED 48 h, ACCEPTED 7 days; EXPIRED, or CANCELLED when `payment_status` is HELD), UC-SY-03 (72 h), UC-SY-06 (60 minutes before, once, not for a cancelled transaction), no-show confirmation. Each takes a batch with `FOR UPDATE SKIP LOCKED` and goes through `TransactionTransitions`.
- **Listeners:** `ListingStatusChanged` (SOLD or REMOVED rejects open offers, LS-W5); `UserSanctioned` (cancel open transactions without a penalty, FR-TX-15).
- **Tests:** PostgreSQL Testcontainers for the unique indexes and the concurrent accept (20 threads give one ACCEPTED); each job run twice changes nothing; a transition interrupted by a thrown exception leaves no history and no event.

#### T3. Public API for other modules

SPIs implemented: `OpenTransactionCheck`, `SellerTradeHistory`, `OpenOfferCounts`, `AreaRemovalBlocker`, `AccountDeletionBlocker`. Facades in the module root: `TransactionLookup` (parties, price, status, `on_hold`, completed count, for Payment, Chat and Reputation), `TransactionHold` (TX-W1..W3), `TransactionPaymentStatus` (Payment writes the copy, D1). Events as listed above. Tests: each SPI, the facade contracts, `ApplicationModules.verify()`.

#### T4. Web

| Method and path | Success | FR / UC / screen |
|---|---|---|
| `POST /transactions` `{listingId, price, note}` | 201 | FR-TX-01, A20 (`Idempotency-Key`) |
| `GET /transactions?role=&status=&cursor=` | 200 | FR-TX-14, A21 |
| `GET /transactions/{id}` | 200 (timeline, appointment, payment, allowed actions) | A22 |
| `POST /transactions/{id}/accept`, `/reject` `{version, reason}` | 200 | FR-TX-03 |
| `POST /transactions/{id}/withdraw` | 200 | FR-TX-04 |
| `POST /transactions/reservations` `{listingId, buyerId, price}` | 201 | FR-TX-05, A18 |
| `POST /transactions/mark-sold` `{listingId, buyerId, price, listingVersion}` | 201 | UC-LS-05, FR-LS-09 (a) |
| `POST /transactions/{id}/deny-sale` | 200 | UC-LS-05 A3 |
| `POST /transactions/{id}/confirm-handover`, `/confirm-receipt` | 200 | FR-TX-08 |
| `POST /transactions/{id}/cancel` `{reasonCode, note, version}` | 200 | FR-TX-10 |
| `POST /transactions/{id}/appointments` `{meetAt, placeName, point, note}` | 201 | FR-TX-07, A19 |
| `POST /appointments/{id}/confirm`, `/decline`, `/cancel`, `/reschedule`, `/report-no-show`, `/contest-no-show` | 200 | FR-TX-07, FR-TX-11 |

All commands need `Idempotency-Key` and, for transitions, the `version`. Another user's transaction is 404.

#### T5. Closing rows inside `chat`

System messages for every event (`SystemMessagePoster`, dedup by event id), notifications of the reserved TRADE types (`NotificationType` entries exist), the `ChatPartners` SPI implementation (CH-W4, with `NOT_A_CHAT_PARTNER`), `openForTrade` called by the offer flow (CH-W5), `ReceiptConfirmed` and `HandoverConfirmed` notifications with the actions of the "sale marked" type. Tests: each event posts exactly one message and one notification per recipient, even when delivered twice.

#### T6. Contract and definition of done

`docs/api/transaction-api.md` (flows FT1..FT9 with A19–A22, state machines, waits, timers), regenerated `openapi.yml`, `docs/postman/nearby-transaction.postman_collection.json`, README and prompts links; checklist 07; `./gradlew build` green; coverage; status file, roadmap, decision log, register; **no new technology in Part A**, so the manual setup line says so; the report.

### Part B: `payment`

#### P0. Groundwork

V18 (six tables, the ledger triggers of D8, parameters, CHECKs, UKs); `payment/package-info.java`; `PAYMENT_AES_KEY`, `app.payment.enabled-providers`, `app.payment.default-provider` and the `ZALOPAY_*` variables in `.env.example`, `deploy/.env.example` and `compose` files; `banks.json`; error codes; `ApiPaths`; webhook path in the security configuration (public, HMAC-protected). **New technology: manual setup and test (skill step 14)** begins here: a section of `docs/setup-local.md` for the AES key and the gateway sandbox.

#### P1. Domain (pure Java, test-first)

| Class | Responsibility | Key tests (SRS numbers) |
|---|---|---|
| `Payment`, `PaymentStatus` | Both methods' states (`tab:sm-payment`) | Every edge; PENDING to EXPIRED at 15 minutes; HELD to RELEASED only when COMPLETED and not on hold |
| `Money`, `SafePaymentFee` | VND, fee rounding | 500 000 gives fee 10 000 and total 510 000; 10 000 and 50 000 000 accepted, 9 999 and 50 000 001 refused (`SAFE_PAYMENT_LIMIT`) |
| `LedgerJournal` and the five builders | CAPTURE, RELEASE, PAYOUT, REFUND_REQUEST, REFUND_SETTLE, REVERSAL | The five rows of the appendix D example; **property test (NFR-27)**: random sequences of success, release, refund, failure and retry keep every journal and the whole ledger at zero and never release during a dispute |
| `Payout`, `PayoutStatus` | `tab:sm-payout`, 24 h lock | PENDING stays until `payout_locked_until`; FAILED back to PENDING |
| `Refund`, `RefundSchedule` | Retries with back-off, then REFUND_FAILED | Attempt counts and the next retry time |
| `VietQrEncoder` | EMVCo payload with CRC16-CCITT, `HL-<code>` memo | A known reference vector for payload and CRC; decoded by at least two banking apps (manual check, see P6) |
| `AccountNumberCipher` | AES-256-GCM `iv ‖ ciphertext` | Round trip; a tampered byte fails; two encryptions of the same number differ |
| `PaymentSignature` | HMAC, 5-minute replay window | Forged, modified and replayed callbacks are rejected (NFR-32) |

#### P2. Application and infrastructure

UC-PM-01 choose a method (CASH default; QR only with a payout account; Safe Payment inside the limits; free items no payment), UC-PM-02 payout account (re-authentication, change deactivates the old row, 24 h lock), UC-PM-03/04 VietQR (local QR, seller confirms), UC-PM-05 Safe Payment (create the gateway order, 15-minute link, 502/503 `GATEWAY_UNAVAILABLE`, `PAYMENT_ALREADY_INITIATED`), UC-PM-07 IPN (save the raw payload first, verify, dedup by `gateway_txn_id`, compare the amount, a success for an EXPIRED or VOIDED payment is recorded and flagged for Finance), the status query after 5 minutes without IPN, UC-SY-13 release on `TransactionCompleted` (one payout per payment, never while on hold), refunds on `TransactionCancelled`, `TransactionExpired` and sanctions with retries and `REFUND_FAILED`, payouts (manual batch or gateway), `AccountDeletionBlocker`. `PaymentGateway` port, `PaymentGatewayRegistry`, `SandboxPaymentGateway`, then `ZaloPayGateway` (signing, create, query, refund, refund query, callback verification and reply) with its contract tests; the registry and the contract tests come first so the second provider is cheap.

#### P3. Public API for other modules

`RefundRequests` (PM-W1), `FinanceOperations` (PM-W2), events listed above, `PaymentLookup` for Transaction's detail. Tests: facade contracts, `ApplicationModules.verify()`.

#### P4. Web

| Method and path | Success | FR / UC / screen |
|---|---|---|
| `GET /transactions/{id}/payment-options` | 200 (each method with enabled flag and reason) | FR-PM-01, A22/A23 |
| `PUT /transactions/{id}/payment-method` | 200 | FR-PM-01 |
| `POST /transactions/{id}/payments/vietqr` | 201 (QR payload and image) | FR-PM-03 (`Idempotency-Key`) |
| `POST /payments/{id}/confirm-received` | 200 | FR-PM-03, UC-PM-04 |
| `POST /transactions/{id}/payments/safe` | 201 (payment URL, expiry) | FR-PM-04 (`Idempotency-Key`) |
| `GET /payments?cursor=`, `GET /payments/{id}` | 200 | FR-PM-10, A25 |
| `GET/PUT /users/me/payout-account` | 200 | FR-PM-02, A24 |
| `POST /payments/webhooks/{provider}` | 200 (provider contract) | FR-PM-05, UC-PM-07 |

#### P5. Closing rows inside `chat`

System messages and notifications for payment and refund events (NT-W2), `PaymentSucceeded` tells the seller to deliver.

#### P6. Contract, manual setup and test, definition of done

`docs/api/payment-api.md` (flows FP1..FP6 with A22–A25), `openapi.yml`, Postman; `docs/setup-local.md` section for the AES key, the gateway sandbox and a public tunnel for IPN callbacks (a gateway cannot reach `localhost`); a **manual test** the way the skill requires: start the stack with the tunnel, create a Safe Payment with provider ZALOPAY, pay in the ZaloPay sandbox app, see the IPN arrive, the ledger balance and the seller notified, then refund; what was run and what needs a human (real gateway account, a bank app scanning the QR) is written down; checklist 07; build; status file; report.

## Order of work (small reviewable steps, each green)

1. T0 groundwork (V17, codes, package-info).
2. T1 domain with its tests.
3. T2 offer, respond, withdraw, reserve; then mark sold and deny; then confirm, complete, cancel; then appointments and no-show; then jobs and listeners.
4. T3 SPIs and facades (this closes the listing, area and identity rows).
5. T4 web and T5 chat listeners (closes the chat rows); T6 contract. **Stop: ask before Part B.**
6. P0 groundwork and manual setup text; P1 domain (ledger property test first); P2 methods and payout account; Safe Payment, IPN, release, refund, payout; P3, P4, P5; P6 manual test, contract, report.

## What I need from you before Phase T0

1. D1 to D14, mainly D1 (two modules), D3 (`VERSION_CONFLICT`), D5 (disputes belong to Moderation) and D6 (which payment gateway).
2. Whether Part A may start before the gateway account exists (recommended: yes).
