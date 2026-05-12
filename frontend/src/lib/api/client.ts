import axios, { type AxiosError, type InternalAxiosRequestConfig } from "axios";

// Empty string = relative URLs, so the browser calls the same host it loaded from.
// Works on any server via nginx. Override with NEXT_PUBLIC_API_URL only for local IDE dev.
const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "";

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    "Content-Type": "application/json",
  },
});

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (typeof window !== "undefined") {
    const token = localStorage.getItem("access_token");
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError & { config?: InternalAxiosRequestConfig & { _retry?: boolean } }) => {
    // 403s are surfaced to the calling component as a normal error — the dashboard, sidebar,
    // and other shells call dozens of endpoints, and not every user can hit every one. Auto-
    // redirecting to /forbidden on any single 403 made the entire app unreachable for non-ADMIN
    // profiles. The middleware still gates direct page navigation; per-widget errors are
    // handled by react-query's error state where they occur.

    if (error.response?.status === 401 && !error.config?._retry) {
      error.config!._retry = true;
      // Skip redirect for login/refresh endpoints — let the caller handle the error
      const requestUrl = error.config?.url || '';
      if (requestUrl.includes('/v1/auth/login') || requestUrl.includes('/v1/auth/refresh')) {
        return Promise.reject(error);
      }
      if (typeof window !== "undefined") {
        const refreshToken = localStorage.getItem("refresh_token");
        if (refreshToken) {
          try {
            const res = await axios.post(`${API_BASE_URL}/v1/auth/refresh`, { refreshToken });
            const newToken = res.data.data.accessToken;
            localStorage.setItem("access_token", newToken);
            document.cookie = `access_token=${newToken}; path=/; max-age=3600; Secure; SameSite=Strict`;
            error.config!.headers.Authorization = `Bearer ${newToken}`;
            return apiClient(error.config!);
          } catch {
            // refresh failed, clear auth
            localStorage.removeItem("access_token");
            localStorage.removeItem("refresh_token");
            document.cookie = 'access_token=; path=/; max-age=0; Secure; SameSite=Strict';
            if (typeof window !== "undefined" && !window.location.pathname.startsWith("/auth/")) {
              window.location.href = "/auth/login";
            }
          }
        } else {
          // no refresh token, redirect to login (but not if already on an auth page)
          localStorage.removeItem("access_token");
          document.cookie = 'access_token=; path=/; max-age=0; Secure; SameSite=Strict';
          if (typeof window !== "undefined" && !window.location.pathname.startsWith("/auth/")) {
            window.location.href = "/auth/login";
          }
        }
      }
    }
    return Promise.reject(error);
  }
);
