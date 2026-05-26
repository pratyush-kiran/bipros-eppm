import { AccessProvider } from "@/lib/auth/AccessProvider";
import { AiChatPanel } from "@/components/ai/AiChatPanel";
import { CommandPalette } from "@/components/command-palette/CommandPalette";
import { AppShell } from "@/components/common/AppShell";

export default function AppLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AccessProvider>
      <AppShell>{children}</AppShell>
      <AiChatPanel />
      <CommandPalette />
    </AccessProvider>
  );
}
