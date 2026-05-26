"use client";

import { useEffect } from "react";
import { Header } from "./Header";

export function AppShell({ children }: { children: React.ReactNode }) {
  // The root layout pins html/body to `h-full` so older sidebar+inner-scroll
  // shells could host an independent scroll container. We use natural browser
  // scroll everywhere now, so release the constraint on mount. Cleanup restores
  // it — defensive in case a future shell ever swaps back at runtime.
  useEffect(() => {
    const html = document.documentElement;
    const body = document.body;
    const hadHtml = html.classList.contains("h-full");
    const hadBody = body.classList.contains("h-full");
    html.classList.remove("h-full");
    body.classList.remove("h-full");
    return () => {
      if (hadHtml) html.classList.add("h-full");
      if (hadBody) body.classList.add("h-full");
    };
  }, []);

  return (
    <div className="min-h-screen bg-ivory">
      <div className="sticky top-0 z-30">
        <Header />
      </div>
      <main className="bg-ivory">
        <div className="px-4 py-6 sm:px-6 lg:px-8">{children}</div>
      </main>
    </div>
  );
}
