# Cinematic as Default Login — Design

**Date:** 2026-05-14
**Author:** hemendra + Claude
**Status:** Approved (design phase) — pending implementation plan

## Goal

Replace the current editorial split-screen login at `/auth/login` with the **cinematic** sample (`/auth/login-samples/cinematic`) so it becomes the canonical sign-in page for Bipros EPPM. Trim controls that aren't backed by real flows yet.

## Why

- Cinematic is the design direction the team is committing to for sign-in.
- Keeping it as a "sample" while `/auth/login` shows a different design means the production sign-in does not match the chosen design.
- Two URLs serving the same UI (Option B in brainstorming) creates ambiguity in the sample gallery.

## Non-goals

- Building real **SSO** flows (Google / Microsoft / SAML). Backend has no SSO endpoints today.
- Building a real **forgot password** flow. No reset endpoint exists today.
- Adjusting the cinematic visual design (no palette tweaks, no copy rewrites beyond removing stub buttons).
- Touching any of the other 8 sample pages or the gallery layout beyond delisting cinematic.
- Changing the auth flow (`useLoginSubmit`) — login behavior is byte-for-byte the same as today.

## Architecture

### Routing — unchanged

`/auth/login` continues to be the sign-in route. All existing callers stay valid:

- `frontend/src/proxy.ts` — redirect target for unauthenticated requests
- `frontend/src/app/forbidden/page.tsx` — "sign in again" link
- `frontend/src/components/common/Sidebar.tsx` — logout redirect
- `frontend/src/lib/api/authApi.ts` and `frontend/src/lib/api/client.ts` — 401-handler redirect

### Component split

Cinematic is currently a single 480-line file. The existing `/auth/login` page uses an `_components/` directory pattern (LoginNav, LoginHero, LoginFeatures, LoginFooter, ScheduleCard). The new cinematic page follows the same convention:

```
frontend/src/app/auth/login/
├── page.tsx                              (rewritten — root layout + <Suspense>)
└── _components/
    ├── CinematicHeader.tsx               (logo + "47 sites live · UTC clock" strip)
    ├── CinematicBackdrop.tsx             (sky/sun/dust/grid/grain/vignette; hosts Skyline + Editorial)
    ├── CinematicSkyline.tsx              (inline-SVG cranes + half-built tower)
    ├── CinematicEditorial.tsx            (eyebrow, headline, body, stats row, quote)
    ├── CinematicForm.tsx                 (glass auth card, hooks into useLoginSubmit)
    └── CinematicKeyframes.tsx            (the @keyframes cnRise <style> block)
```

`page.tsx` keeps the `<Suspense>` boundary wrapping `<CinematicForm />` — Next.js 16 requires `useSearchParams()` (called inside `useLoginSubmit`) to live inside Suspense.

### Auth flow — unchanged

`CinematicForm.tsx` calls `useLoginSubmit` from `frontend/src/app/auth/login-samples/_shared/useLoginSubmit.ts` (already imported by the existing cinematic sample). No changes to:

- Token handling (cookie + localStorage prime)
- `/v1/users/me` call
- `setAuth` in the auth store
- `safeNext` redirect logic (defends against `//` external redirects)
- Error mapping (401 → "Invalid username or password", 5xx → service unavailable, fallback)

### Trimmed controls

In the new `CinematicForm.tsx`, **remove** (do not re-create):

- The 3-button SSO row (`<SsoBtn label="Google" />` etc.) and the "or with email" divider above the username field.
- The "Forgot?" button next to the password label.

Everything else from the cinematic sample stays: username / password fields, "Keep me signed in for 7 days" checkbox, gradient Sign-in button, security footer (JWT-bound badge, SOC 2 / ISO 27001 / GDPR chips), "New here? Take the tour →" link.

The `lucide-react` `Lock`, `Eye`, `EyeOff`, `ShieldCheck`, `ArrowUpRight` imports stay (still used by the trimmed form).

### Sample gallery

`frontend/src/app/auth/login-samples/page.tsx` — remove the cinematic entry from the `SAMPLES` array. Other entries keep their existing `n:` labels; we accept a numbering gap rather than renumber (numbers are stable design references in chat history).

The sample route `/auth/login-samples/cinematic` is removed entirely.

## Data flow

```
User → /auth/login
       ↓
   page.tsx
       ├─ <CinematicHeader />
       └─ <main grid>
            ├─ <CinematicBackdrop>
            │     ├─ <CinematicSkyline />
            │     └─ <CinematicEditorial />
            └─ <Suspense fallback="Loading…">
                  └─ <CinematicForm>
                        └─ useLoginSubmit()
                              ├─ authApi.login()
                              ├─ authApi.me()
                              └─ window.location.href = safeNext
```

No new API calls. No new state. No new dependencies.

## Files added

| File | Purpose |
|---|---|
| `frontend/src/app/auth/login/_components/CinematicHeader.tsx` | Header bar |
| `frontend/src/app/auth/login/_components/CinematicBackdrop.tsx` | Backdrop section (gradients, grain, vignette) |
| `frontend/src/app/auth/login/_components/CinematicSkyline.tsx` | Inline-SVG skyline + cranes |
| `frontend/src/app/auth/login/_components/CinematicEditorial.tsx` | Left-column editorial copy + stats |
| `frontend/src/app/auth/login/_components/CinematicForm.tsx` | Auth panel (uses `useLoginSubmit`) |
| `frontend/src/app/auth/login/_components/CinematicKeyframes.tsx` | `@keyframes cnRise` style block |

## Files modified

| File | Change |
|---|---|
| `frontend/src/app/auth/login/page.tsx` | Rewritten — composes the cinematic components, wraps form in `<Suspense>`. |
| `frontend/src/app/auth/login-samples/page.tsx` | Remove cinematic entry from `SAMPLES` array. |

## Files deleted

- `frontend/src/app/auth/login/_components/LoginNav.tsx`
- `frontend/src/app/auth/login/_components/LoginHero.tsx`
- `frontend/src/app/auth/login/_components/LoginFeatures.tsx`
- `frontend/src/app/auth/login/_components/LoginFooter.tsx`
- `frontend/src/app/auth/login/_components/ScheduleCard.tsx`
- `frontend/src/app/auth/login-samples/cinematic/page.tsx` (and the empty `cinematic/` directory)

## Error handling

Inherited from `useLoginSubmit` — no changes:

- 401 → "Invalid username or password." rendered in the in-card alert.
- 5xx → "Sign-in service is unavailable. Please try again in a moment."
- Other → "Could not sign you in. Please try again."

Reduced-motion preference is already respected via the `@media (prefers-reduced-motion: reduce)` rule in `CinematicKeyframes.tsx`.

## Testing

- **Manual smoke:** load `/auth/login`, sign in as `admin / admin123`, confirm redirect to `/` (or `?next=...` when present). Toggle "Show password" eye, "Keep me signed in" checkbox.
- **Manual visual:** confirm cinematic backdrop renders correctly at mobile (single column, scrim on top of photo) and desktop (`lg:grid-cols-[1.5fr_1fr]`).
- **Existing Playwright e2e:** any spec that visits `/auth/login` should still pass since the form fields keep their `autoComplete="username" / "current-password"` semantics. The `id` attributes (`cn-user`, `cn-pwd`) change from whatever the editorial form used today; if a Playwright spec selects by ID we will update the selector to use `[autocomplete="username"]` / `[autocomplete="current-password"]` for stability.

## Risk

- **Low.** Pure UI swap on a route that already exists. Auth flow is unchanged. No backend changes. No new dependencies.
- The only behavioral change visible to users is the removed SSO + Forgot stubs — which today do nothing on the editorial page either (the editorial form has no SSO row, so this is only a regression vs. the cinematic *sample*, not vs. production).

## Open questions

None. Recommendations were approved by the user during brainstorming.
