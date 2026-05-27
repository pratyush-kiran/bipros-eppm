"use client";

import { useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { isAxiosError } from "axios";
import { authApi } from "@/lib/api/authApi";
import { useAuthStore } from "@/lib/state/store";
import { withBasePath } from "@/lib/basePath";

export type LoginState = {
  username: string;
  setUsername: (v: string) => void;
  password: string;
  setPassword: (v: string) => void;
  showPassword: boolean;
  setShowPassword: (v: boolean | ((p: boolean) => boolean)) => void;
  remember: boolean;
  setRemember: (v: boolean) => void;
  submitting: boolean;
  fieldError: string | null;
  handleSubmit: (e: FormEvent<HTMLFormElement>) => Promise<void>;
};

export function useLoginSubmit(): LoginState {
  const searchParams = useSearchParams();
  const setAuth = useAuthStore((s) => s.setAuth);

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [remember, setRemember] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);

  const nextRaw = searchParams.get("next");
  const safeNext =
    nextRaw && nextRaw.startsWith("/") && !nextRaw.startsWith("//") ? nextRaw : "/";

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setFieldError(null);
    setSubmitting(true);
    try {
      const loginRes = await authApi.login({ username, password });
      const accessToken = loginRes.data?.accessToken;
      const refreshToken = loginRes.data?.refreshToken;
      if (!accessToken || !refreshToken) {
        throw new Error("Login response missing tokens");
      }
      document.cookie = `access_token=${accessToken}; path=/; max-age=3600; SameSite=Strict`;
      localStorage.setItem("access_token", accessToken);
      localStorage.setItem("refresh_token", refreshToken);

      const meRes = await authApi.me();
      const user = meRes.data;
      if (!user) throw new Error("Failed to load current user");
      setAuth(user, accessToken, refreshToken);

      // Raw navigation (full reload to re-run the proxy auth gate) → prefix the
      // in-app `next` path with the basePath, which window.location won't add.
      window.location.href = withBasePath(safeNext);
    } catch (err) {
      if (isAxiosError(err) && err.response?.status === 401) {
        setFieldError("Invalid username or password.");
      } else if (isAxiosError(err) && err.response && err.response.status >= 500) {
        setFieldError("Sign-in service is unavailable. Please try again in a moment.");
      } else {
        setFieldError("Could not sign you in. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return {
    username,
    setUsername,
    password,
    setPassword,
    showPassword,
    setShowPassword,
    remember,
    setRemember,
    submitting,
    fieldError,
    handleSubmit,
  };
}
