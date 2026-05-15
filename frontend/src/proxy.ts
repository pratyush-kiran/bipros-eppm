import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // Skip static assets in /public — they have a file extension and are served
  // from the project root (e.g. /bipros-logo.png). Without this, the unauth
  // redirect below turns every image/font request into an HTML redirect.
  if (/\.[a-zA-Z0-9]+$/.test(pathname)) {
    return NextResponse.next();
  }

  // Next.js metadata routes (`app/icon.png`, `app/apple-icon.png`, etc.) are
  // served as extension-less paths like `/icon` with a hash query string.
  // Without this bypass, browser favicon requests get redirected to /auth/login.
  if (/^\/(icon|apple-icon|opengraph-image|twitter-image)(\b|\d|\/)/.test(pathname)) {
    return NextResponse.next();
  }

  const token = request.cookies.get('access_token')?.value;
  const isAuthPage = pathname.startsWith('/auth');
  const isForbiddenPage = pathname === '/forbidden';
  // Public pages that should render for both signed-out and signed-in users.
  const isPublicPage = pathname === '/welcome' || pathname.startsWith('/welcome/');

  if (!token && !isAuthPage && !isPublicPage) {
    // Preserve where the user was trying to go so the login form can return them after auth.
    const next = pathname + (request.nextUrl.search || '');
    const loginUrl = new URL('/auth/login', request.url);
    if (pathname !== '/' && !pathname.startsWith('/auth')) {
      loginUrl.searchParams.set('next', next);
    }
    return NextResponse.redirect(loginUrl);
  }

  if (token && isAuthPage) {
    return NextResponse.redirect(new URL('/', request.url));
  }

  // Admin-area UX guard: decode the JWT payload (best-effort, no signature check — that's
  // the backend's job) and short-circuit to /forbidden if the user lacks ROLE_ADMIN. The
  // backend still enforces; this just spares users an awkward "page loads then errors" flow.
  //
  // If decoding fails (malformed token, JWT format change, outage that scrambled cookies),
  // we log a warning AND treat /admin paths as denied. Without the deny, a broken decoder
  // could silently let any authenticated user reach the admin shell — the backend would still
  // reject the API calls, but the UI would render and look reachable, which is the security
  // smell we want to avoid.
  if (token && pathname.startsWith('/admin') && !isForbiddenPage) {
    const roles = decodeRolesFromJwt(token);
    if (roles === null) {
      console.warn(
        '[middleware] Could not decode JWT roles claim for path',
        pathname,
        '- treating /admin as denied. Investigate token shape if this recurs.',
      );
      return NextResponse.redirect(new URL('/forbidden', request.url));
    }
    if (!roles.includes('ROLE_ADMIN')) {
      return NextResponse.redirect(new URL('/forbidden', request.url));
    }
  }

  // Cross-portfolio / master-data UX guards. Each route prefix declares the
  // permission its tiles in `hubConfig.ts` already require — duplicating here
  // catches direct-URL navigation (bookmark, deep link). Backend remains the
  // authoritative gate; this just spares the user a "loads then 403s" flow.
  if (token && !isForbiddenPage) {
    const required = requiredPermissionFor(pathname);
    if (required) {
      const perms = decodePermsFromJwt(token);
      if (perms === null || (!perms.has('*') && !perms.has(required))) {
        return NextResponse.redirect(new URL('/forbidden', request.url));
      }
    }
  }

  return NextResponse.next();
}

/** Map of path prefix → permission code required to view it. Order matters:
 *  the first prefix that matches wins. Keep prefixes specific to avoid
 *  accidentally gating sub-routes that belong to a different perm domain. */
const ROUTE_PERMISSION_PREFIXES: Array<readonly [string, string]> = [
  ['/obs', 'ADMIN_ORG.READ'],
  ['/portfolios', 'PORTFOLIO.READ'],
  ['/eps', 'PORTFOLIO.READ'],
  ['/labour-master', 'ADMIN_MASTER.READ'],
  ['/resources', 'ADMIN_MASTER.READ'],
  ['/dashboards', 'PORTFOLIO.READ'],
  ['/dashboard', 'PORTFOLIO.READ'],
  ['/analytics', 'AI.WRITE'],
  ['/reports/risk-register', 'RISK.READ'],
  ['/reports', 'REPORT.EXPORT'],
];

function requiredPermissionFor(pathname: string): string | null {
  for (const [prefix, perm] of ROUTE_PERMISSION_PREFIXES) {
    if (pathname === prefix || pathname.startsWith(`${prefix}/`)) {
      return perm;
    }
  }
  return null;
}

/** Best-effort decode of the comma-joined `perms` claim. Returns null on any failure. */
function decodePermsFromJwt(token: string): Set<string> | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payloadJson = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'));
    const payload = JSON.parse(payloadJson) as { perms?: unknown; roles?: unknown };
    // ADMIN role bypasses — the backend matrix maps ADMIN to every code; keep this
    // mirror so we don't have to enumerate them in the JWT to satisfy the guard.
    if (Array.isArray(payload.roles) && payload.roles.some((r) => typeof r === 'string' && (r === 'ADMIN' || r === 'ROLE_ADMIN'))) {
      return new Set(['*']);
    }
    if (typeof payload.perms !== 'string' || payload.perms.length === 0) return new Set();
    const set = new Set(payload.perms.split(','));
    // Sentinel so `set.has(perm)` for any perm returns true when admin.
    return set;
  } catch {
    return null;
  }
}

/** Best-effort JWT role extraction. Returns {@code null} on any decoding failure; the caller
 *  decides whether to treat that as a deny (admin areas) or fall through (regular routes). */
function decodeRolesFromJwt(token: string): string[] | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payloadJson = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'));
    const payload = JSON.parse(payloadJson) as { roles?: unknown };
    if (!Array.isArray(payload.roles)) return null;
    return payload.roles
      .map((r) => (typeof r === 'string' ? (r.startsWith('ROLE_') ? r : `ROLE_${r}`) : null))
      .filter((r): r is string => r !== null);
  } catch {
    return null;
  }
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|icon|apple-icon|opengraph-image|twitter-image|api|forbidden).*)'],
};
