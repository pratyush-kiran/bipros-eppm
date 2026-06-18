import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface BrandingResponse {
  logoLight: string | null;
  logoDark: string | null;
  appNamePrimary: string | null;
  appNameSecondary: string | null;
}

export const brandingApi = {
  /** Public — no auth. Returns the globally-active theme's logos + app name. */
  getBranding: () =>
    apiClient
      .get<ApiResponse<BrandingResponse>>("/v1/public/branding")
      .then((r) => r.data),
};
