import { findGroupById, type SectionGroupId } from "../../_data/projectNav";
import { cn } from "@/lib/utils/cn";

export function GroupChip({ groupId, className }: { groupId: SectionGroupId; className?: string }) {
  const g = findGroupById(groupId);
  if (!g) return null;
  return (
    <span className={cn(
      "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.12em]",
      g.chipClass,
      className,
    )}>
      {g.label}
    </span>
  );
}
