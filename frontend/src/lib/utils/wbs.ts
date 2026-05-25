import type { WbsNodeResponse } from "@/lib/types";

export interface FlatWbsNode {
  id: string;
  code: string;
  name: string;
  indent: string;
}

export function flattenWbsNodes(
  nodes: WbsNodeResponse[],
  level = 0
): FlatWbsNode[] {
  const result: FlatWbsNode[] = [];
  for (const node of nodes) {
    result.push({
      id: node.id,
      code: node.code,
      name: node.name,
      indent: "  ".repeat(level),
    });
    if (node.children && node.children.length > 0) {
      result.push(...flattenWbsNodes(node.children, level + 1));
    }
  }
  return result;
}

export interface WbsNodeInfo {
  name: string;
  code: string;
  sortOrder: number;
}

export function buildWbsNameMap(
  nodes: WbsNodeResponse[]
): Map<string, WbsNodeInfo> {
  const map = new Map<string, WbsNodeInfo>();
  const walk = (list: WbsNodeResponse[]) => {
    for (const node of list) {
      map.set(node.id, {
        name: node.name,
        code: node.code,
        sortOrder: node.sortOrder ?? 0,
      });
      if (node.children && node.children.length > 0) {
        walk(node.children);
      }
    }
  };
  walk(nodes);
  return map;
}
