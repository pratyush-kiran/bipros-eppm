import type { Metadata } from "next";
import { Fraunces, Inter, JetBrains_Mono } from "next/font/google";
import localFont from "next/font/local";
import "./globals.css";
import { Providers } from "@/lib/providers";
import { AppToaster } from "@/components/common/Toaster";
import { ThemeProvider } from "@/components/theme/ThemeProvider";
import { BfcacheAuthSync } from "@/lib/auth/BfcacheAuthSync";

// Inline theme bootstrap injected into the SSR HTML so cached CSS vars apply
// before hydration (avoids the flash of unstyled theme). Per Next.js 16 docs
// (app/02-guides/json-ld.md), a native lowercase <script> in a Server Component
// is the right primitive for inline payloads — next/script is for external
// loadable scripts and now triggers a React 19 warning when used with
// dangerouslySetInnerHTML on the client tree.
const themeInitScript = `(function(){try{var c=localStorage.getItem('bipros-theme-cache');if(c){var e=document.createElement('style');e.id='bipros-theme-vars';document.head.appendChild(e);e.textContent=c;}}catch(e){}})();`;

// Client-side mirror of the server route guard (proxy.ts), injected inline so it runs
// synchronously on EVERY document parse — including Back/Forward navigations the browser serves
// from its cache without a server request, where proxy.ts never runs. Without this, pressing Back
// after logout could show a cached authenticated page (and after login, the cached sign-in form),
// because the cached HTML is rendered without re-checking the session. We key off the same
// access_token cookie the proxy uses, so the two never disagree. (True bfcache restores, where the
// document is not re-parsed, are handled separately by BfcacheAuthSync's pageshow listener.)
const authGuardScript = `(function(){try{var m=document.cookie.match(/(?:^|; )access_token=([^;]*)/);var hasToken=!!(m&&m[1]);var p=location.pathname;var onAuth=p==='/auth'||p.indexOf('/auth/')===0;var pub=p==='/welcome'||p.indexOf('/welcome/')===0||p==='/forbidden';if(!hasToken&&!onAuth&&!pub){location.replace('/auth/login');}else if(hasToken&&onAuth){location.replace('/');}}catch(e){}})();`;

const fraunces = Fraunces({
  subsets: ["latin"],
  axes: ["opsz"],
  variable: "--font-fraunces",
  display: "swap",
});

const inter = Inter({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-inter",
  display: "swap",
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-jetbrains",
  display: "swap",
});

// Self-hosted Material Symbols icon font. Loading it via next/font/local means
// Next serves it from /_next/static/media (basePath-prefixed automatically), so
// there's no hardcoded path to keep in sync with the base path.
const materialSymbols = localFont({
  src: "../../public/fonts/material-symbols-outlined.woff2",
  variable: "--font-material-symbols",
  display: "block",
  weight: "100 700",
});

export const metadata: Metadata = {
  title: "Bipros EPPM",
  description: "Enterprise Project Portfolio Management System",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html
      lang="en"
      className={`${fraunces.variable} ${inter.variable} ${jetbrainsMono.variable} ${materialSymbols.variable} h-full antialiased`}
      suppressHydrationWarning
    >
      <body className="h-full bg-background text-foreground">
        <script
          id="bipros-auth-guard"
          dangerouslySetInnerHTML={{ __html: authGuardScript }}
        />
        <script
          id="bipros-theme-init"
          dangerouslySetInnerHTML={{ __html: themeInitScript }}
        />
        <BfcacheAuthSync />
        <ThemeProvider>
          <Providers>{children}</Providers>
        </ThemeProvider>
        <AppToaster />
      </body>
    </html>
  );
}
