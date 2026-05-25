import { isAxiosError } from "axios";
import type { ApiResponse } from "../types";

/**
 * Extract a user-facing message from an unknown error. Backend wraps errors in
 * {@link ApiResponse}'s `error.message` envelope; without this helper, callers fall back to
 * axios's default `"Request failed with status code 400"`, which hides the actual cause from
 * the user.
 *
 * Order of fallbacks: ApiResponse.error.message → first field reason → generic Error.message
 * → provided default.
 */
export function extractApiError(err: unknown, fallback: string): string {
  if (isAxiosError(err)) {
    const data = err.response?.data as ApiResponse<unknown> | undefined;
    if (data?.error?.message) return data.error.message;
    const firstField = data?.error?.details?.[0];
    if (firstField?.reason) {
      return firstField.field
        ? `${firstField.field}: ${firstField.reason}`
        : firstField.reason;
    }
  }
  if (err instanceof Error && err.message) return err.message;
  return fallback;
}
