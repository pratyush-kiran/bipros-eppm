"use client";

import { HubBanner } from "./HubBanner";
import { RecentProjectsStrip } from "./RecentProjectsStrip";
import { FeaturedStrip } from "./FeaturedStrip";
import { ModuleSection } from "./ModuleSection";
import { MODULE_SECTIONS } from "./modulesConfig";
import { useModuleAccess } from "./hooks/useModuleAccess";

export function MissionControl() {
  const { canSee } = useModuleAccess();

  return (
    <div data-testid="mission-control-root" className="w-full">
      <HubBanner />
      <RecentProjectsStrip />
      <FeaturedStrip />

      {MODULE_SECTIONS.map((section) => (
        <ModuleSection
          key={section.label}
          section={section}
          tiles={section.tiles.filter(canSee)}
        />
      ))}
    </div>
  );
}
