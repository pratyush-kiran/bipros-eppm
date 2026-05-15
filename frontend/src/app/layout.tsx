import type { Metadata } from "next";
import { Fraunces, Inter, JetBrains_Mono } from "next/font/google";
import Script from "next/script";
import "./globals.css";
import { Providers } from "@/lib/providers";
import { AppToaster } from "@/components/common/Toaster";
import { ThemeProvider } from "@/components/theme/ThemeProvider";

// Inline theme bootstrap. Runs before hydration via next/script's
// beforeInteractive strategy so the cached CSS variables are applied to <head>
// immediately and we avoid a flash of unstyled theme. React 19 forbids raw
// <script> elements rendered from components, so we route this through the
// official next/script component.
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
        <Script
          id="bipros-theme-init"
          strategy="beforeInteractive"
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
