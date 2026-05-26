import type { Metadata } from "next";
import { Fraunces, Inter, JetBrains_Mono } from "next/font/google";
import "./globals.css";
import { Providers } from "@/lib/providers";
import { AppToaster } from "@/components/common/Toaster";
import { ThemeProvider } from "@/components/theme/ThemeProvider";

// Inline theme bootstrap injected into the SSR HTML so cached CSS vars apply
// before hydration (avoids the flash of unstyled theme). Per Next.js 16 docs
// (app/02-guides/json-ld.md), a native lowercase <script> in a Server Component
// is the right primitive for inline payloads — next/script is for external
// loadable scripts and now triggers a React 19 warning when used with
// dangerouslySetInnerHTML on the client tree.
const themeInitScript = `(function(){try{var c=localStorage.getItem('bipros-theme-cache');if(c){var e=document.createElement('style');e.id='bipros-theme-vars';document.head.appendChild(e);e.textContent=c;}}catch(e){}})();`;

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
      className={`${fraunces.variable} ${inter.variable} ${jetbrainsMono.variable} h-full antialiased`}
      suppressHydrationWarning
    >
      <body className="h-full bg-background text-foreground">
        <script
          id="bipros-theme-init"
          dangerouslySetInnerHTML={{ __html: themeInitScript }}
        />
        <ThemeProvider>
          <Providers>{children}</Providers>
        </ThemeProvider>
        <AppToaster />
      </body>
    </html>
  );
}
