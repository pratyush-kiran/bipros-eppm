/**
 * App base path — the sub-path the whole frontend is served under.
 *
 * Driven by the single env var `NEXT_PUBLIC_BASE_PATH` (e.g. `/v2`). Leave it
 * unset/blank to serve at the domain root. `next.config.ts` imports
 * {@link BASE_PATH} from here so the config and the runtime helpers can never
 * disagree — one switch, one normalisation.
 *
 * `next/link`, `useRouter` (next/navigation) and the `next/*` runtime prepend
 * this automatically. Use {@link withBasePath} only where Next does NOT —
 * raw `window.location` navigations and static asset URLs in `<img src>`.
 */

/** Coerce any env value to a valid Next basePath: "" or a "/"-prefixed,
 *  non-trailing-slash string. `undefined`, `""` and `"/"` all mean "no base". */
export function normalizeBasePath(raw: string | undefined | null): string {
  const trimmed = (raw ?? "").trim();
  if (trimmed === "" || trimmed === "/") return "";
  const withLead = trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
  return withLead.endsWith("/") ? withLead.slice(0, -1) : withLead;
}

export const BASE_PATH = normalizeBasePath(process.env.NEXT_PUBLIC_BASE_PATH);

/**
 * Prefix an in-app absolute path with {@link BASE_PATH}. Leaves external URLs
 * (`http(s)://…`), protocol-relative (`//…`) and already-relative values
 * untouched, so it is safe to wrap theme-configured logo URLs and redirect
 * targets indiscriminately. A no-op when no base path is configured.
 */
export function withBasePath(path: string): string {
  if (!BASE_PATH || !path || !path.startsWith("/") || path.startsWith("//")) return path;
  return `${BASE_PATH}${path}`;
}

/**
 * Remove a leading {@link BASE_PATH} from a path. Use when handing a
 * `window.location.pathname` (which includes the basePath) to `useRouter`'s
 * push/replace, which prepend the basePath themselves — passing the raw value
 * would double it (`/v2/v2/…`). A no-op when no base path is configured.
 */
export function stripBasePath(path: string): string {
  if (BASE_PATH && path.startsWith(BASE_PATH)) {
    const rest = path.slice(BASE_PATH.length);
    if (rest === "" || rest.startsWith("/")) return rest || "/";
  }
  return path;
}
