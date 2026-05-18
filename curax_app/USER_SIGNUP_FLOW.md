# CuraX — User signup / activation flow (saved spec)

This document captures the intended **production-style** user onboarding flows discussed for CuraX. Use it when implementing Android screens + `medicine-alerts` backend.

---

## Flow A — Admin code first (no email OTP)

### Step 1 — User signup (app)

- **UI:** Email, Password (then continue).
- **Backend:** Create / update user with  
  `user.status = "PENDING"`  
  User exists in DB but is **not** allowed full app features yet.

### Step 2 — Verification screen (app)

- **Title (example):** “Verify Your Access”
- **Field:** Admin connection code  
- **Optional UX:** “Request code from admin” (informational / deep link / message — product decision).

### Step 3 — Code verification (backend)

- **If code valid:**  
  `user.status = "ACTIVE"`  
  → Full app access (notifications, sockets, data sync, etc.).
- **If code invalid:**  
  Keep `user.status = "PENDING"` (no escalation).

### Step 4 — Auto cleanup (backend, important)

- **Rule:** If `user.status == "PENDING"` for **24 hours** (or chosen TTL) → **delete user** (and dependent rows per your FK / cascade policy).
- **Why:** Reduces fake / abandoned signups and keeps DB lean.

---

## Flow B — Email verify first, then admin code (stronger, no wasted admin codes)

### Step 1 — Signup (app)

- **UI:** Email, Password.
- **Backend:**  
  `user.status = "PENDING_EMAIL"`

### Step 2 — Email verification (app + backend)

- Send **OTP or magic link** to email.
- **UI (example):** “Verify your email”
- **On success:**  
  `user.status = "PENDING_ADMIN"`

### Step 3 — Admin connection code (app)

- **UI:** “Enter admin connection code”
- **On success:**  
  `user.status = "ACTIVE"`  
  → Full app access.

### Why this order?

1. **Email first** — Filters bogus emails before admin spends a connection slot / support.
2. **Admin code after** — Only **verified-email** identities use the code → better security and less abuse.

---

## Status enum (suggested)

| Status           | Meaning                          | App should allow                          |
|------------------|----------------------------------|-------------------------------------------|
| `PENDING_EMAIL`  | Awaiting email proof             | Only email verification UI / resend      |
| `PENDING_ADMIN`  | Email OK, awaiting admin code    | Only admin code screen                    |
| `PENDING`        | (Flow A) Awaiting admin only     | Only verification / admin code screen     |
| `ACTIVE`         | Fully onboarded                  | Main app, push, realtime, etc.            |

**Backend enforcement:** Every protected API should check `status == ACTIVE` (or equivalent) and return **403 + clear message** for non-active users so the app can route to the right step.

---

## UX principles

- **Separate screens** for each step are OK; the experience should still feel **one continuous flow** (short transitions, optional success tick before auto-navigate).
- **Avoid** putting **email OTP** and **admin code** on the **same** screen — confusing and error-prone.

### Optional polish

- **Step indicator** (e.g. “Step 1: Email ✓ → Step 2: Admin code”) on one screen *or* as a thin header across steps — especially if you later collapse some steps.

---

## Relation to current CuraX build

- Today: local signup + PIN without central `status` column — this file is the **target** model when central user table + APIs exist.
- When implementing: add migration for `status`, TTL job (cron / worker) for `PENDING*` cleanup, and gate `LaunchActivity` / API client by status → correct screen.

---

*Saved from product discussion. Update this file when flows or TTL change.*
