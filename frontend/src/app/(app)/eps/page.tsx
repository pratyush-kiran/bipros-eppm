"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ChevronsDownUp, ChevronsUpDown, Plus, X, Trash2, Edit2, Check, FolderPlus } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { projectApi } from "@/lib/api/projectApi";
import { TreeView, type TreeViewHandle } from "@/components/common/TreeView";
import { TreeSearchInput } from "@/components/common/TreeSearchInput";
import { PageHeader } from "@/components/common/PageHeader";
import { TabTip } from "@/components/common/TabTip";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { apiClient } from "@/lib/api/client";
import { notificationHelpers } from "@/lib/notificationHelpers";
import type { EpsNodeResponse, ApiResponse, NodeSearchResult } from "@/lib/types";

interface EpsNodeCreateRequest {
  code: string;
  name: string;
  parentId?: string;
}

export default function EpsPage() {
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null);
  const [editingName, setEditingName] = useState("");
  const [formData, setFormData] = useState<EpsNodeCreateRequest>({ code: "", name: "", parentId: undefined });
  const [parentLabel, setParentLabel] = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ open: boolean; nodeId: string | null }>({ open: false, nodeId: null });
  const treeRef = useRef<TreeViewHandle>(null);

  const { data: epsData, isLoading, error } = useQuery({
    queryKey: ["eps"],
    queryFn: () => projectApi.getEpsTree(),
  });

  const createMutation = useMutation({
    mutationFn: async (data: EpsNodeCreateRequest) => {
      const response = await apiClient.post<ApiResponse<EpsNodeResponse>>("/v1/eps", data);
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["eps"] });
      setFormData({ code: "", name: "", parentId: undefined });
      setParentLabel(null);
      setShowForm(false);
      notificationHelpers.creationSuccess("EPS node");
    },
    onError: (err) => notificationHelpers.handleApiError(err, "Couldn't create the EPS node"),
  });

  const updateMutation = useMutation({
    mutationFn: async ({ nodeId, name }: { nodeId: string; name: string }) => {
      const response = await apiClient.put<ApiResponse<EpsNodeResponse>>(`/v1/eps/${nodeId}`, { name });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["eps"] });
      setEditingNodeId(null);
      setEditingName("");
      notificationHelpers.updateSuccess("EPS node");
    },
    onError: (err) => notificationHelpers.handleApiError(err, "Couldn't update the EPS node"),
  });

  const deleteMutation = useMutation({
    mutationFn: async (nodeId: string) => {
      return apiClient.delete(`/v1/eps/${nodeId}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["eps"] });
      notificationHelpers.deletionSuccess("EPS node");
    },
    onError: (err) => {
      notificationHelpers.handleApiError(err, "Couldn't delete the EPS node");
      // Refresh in case the node was already removed elsewhere (stale 404).
      queryClient.invalidateQueries({ queryKey: ["eps"] });
    },
  });

  const moveMutation = useMutation({
    mutationFn: async ({ nodeId, newParentId }: { nodeId: string; newParentId: string | null }) => {
      return projectApi.moveEpsNode(nodeId, newParentId);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["eps"] });
    },
    onError: (err) => {
      notificationHelpers.handleApiError(err, "Couldn't move the EPS node");
      // Revert the optimistic drag by reloading the server tree.
      queryClient.invalidateQueries({ queryKey: ["eps"] });
    },
  });

  const epsNodes = epsData?.data ?? [];

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (formData.code && formData.name) {
      createMutation.mutate(formData);
    }
  };

  const handleAddChild = (parentId: string, parentCode: string, parentName: string) => {
    setFormData({ code: "", name: "", parentId });
    setParentLabel(`${parentCode} - ${parentName}`);
    setShowForm(true);
  };

  const handleAddRoot = () => {
    setFormData({ code: "", name: "", parentId: undefined });
    setParentLabel(null);
    setShowForm(!showForm);
  };

  const handleStartEdit = (nodeId: string, currentName: string) => {
    setEditingNodeId(nodeId);
    setEditingName(currentName);
  };

  const handleSaveEdit = (nodeId: string) => {
    if (editingName.trim()) {
      updateMutation.mutate({ nodeId, name: editingName });
    }
  };

  const handleDelete = (nodeId: string) => {
    setDeleteConfirm({ open: true, nodeId });
  };

  const confirmDelete = useCallback(() => {
    if (deleteConfirm.nodeId) {
      deleteMutation.mutate(deleteConfirm.nodeId);
    }
    setDeleteConfirm({ open: false, nodeId: null });
  }, [deleteConfirm.nodeId, deleteMutation]);

  const cancelDelete = useCallback(() => {
    setDeleteConfirm({ open: false, nodeId: null });
  }, []);

  const handleSearchSelect = useCallback((result: NodeSearchResult) => {
    treeRef.current?.revealNode(result.ancestorIds, result.id);
  }, []);

  const searchEps = useCallback(
    (q: string, page: number, size: number) => projectApi.searchEps(q, page, size),
    []
  );

  return (
    <div>
      <PageHeader
        title="Enterprise Project Structure"
        description="Manage the EPS hierarchy and related projects"
        actions={
          <button
            onClick={handleAddRoot}
            className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
          >
            <Plus size={16} />
            Add Root Node
          </button>
        }
      />

      <TabTip
        title="Enterprise Project Structure (EPS)"
        description="The EPS is the top-level organizational hierarchy for all projects. Think of it as departments or divisions. Each project must belong to an EPS node."
        steps={["Create root nodes for your organization structure", "Hover over a node and click the folder icon to add child nodes", "Projects are assigned to EPS nodes when created"]}
      />

      {showForm && (
        <div className="mb-6 rounded-xl border border-border bg-surface/50 p-4 shadow-lg">
          <form onSubmit={handleSubmit} className="space-y-3">
            {parentLabel && (
              <div className="text-sm text-text-secondary">
                Adding child under: <span className="font-medium text-accent">{parentLabel}</span>
              </div>
            )}
            {!parentLabel && (
              <div className="text-sm text-text-secondary">Adding root-level node</div>
            )}
            <div className="flex gap-2">
              <input
                type="text"
                placeholder="Code"
                value={formData.code}
                onChange={(e) => setFormData({ ...formData, code: e.target.value })}
                className="flex-1 rounded-md border border-border bg-surface-hover px-3 py-2 text-sm placeholder-text-muted text-text-primary focus:border-accent focus:outline-none"
                required
                autoFocus
              />
              <input
                type="text"
                placeholder="Name"
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                className="flex-1 rounded-md border border-border bg-surface-hover px-3 py-2 text-sm placeholder-text-muted text-text-primary focus:border-accent focus:outline-none"
                required
              />
            </div>
            <div className="flex gap-2">
              <button
                type="submit"
                disabled={createMutation.isPending}
                className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
              >
                {createMutation.isPending ? "Creating..." : "Create"}
              </button>
              <button
                type="button"
                onClick={() => { setShowForm(false); setParentLabel(null); setFormData({ code: "", name: "", parentId: undefined }); }}
                className="rounded-md border border-border px-3 py-2 text-sm text-text-secondary hover:bg-surface-hover/50"
              >
                <X size={16} />
              </button>
            </div>
          </form>
        </div>
      )}

      {isLoading && (
        <div className="py-12 text-center text-text-muted">Loading EPS structure...</div>
      )}

      {error && (
        <div className="rounded-md bg-danger/10 p-4 text-sm text-danger">
          Failed to load EPS structure. Is the backend running?
        </div>
      )}

      {!isLoading && epsNodes.length === 0 && (
        <div className="rounded-lg border border-dashed border-border py-12 text-center">
          <p className="text-text-muted">No EPS nodes yet. Create your first node to get started.</p>
        </div>
      )}

      {epsNodes.length > 0 && (
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <TreeSearchInput
              searchFn={searchEps}
              onSelect={handleSearchSelect}
              placeholder="Search EPS by code or name…"
              className="w-full max-w-xs"
            />
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => treeRef.current?.expandAll()}
                className="flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs font-medium text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary"
                title="Expand all nodes"
              >
                <ChevronsUpDown size={14} />
                Expand All
              </button>
              <button
                type="button"
                onClick={() => treeRef.current?.collapseAll()}
                className="flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs font-medium text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary"
                title="Collapse all nodes"
              >
                <ChevronsDownUp size={14} />
                Collapse All
              </button>
            </div>
          </div>
          <TreeView
            ref={treeRef}
            nodes={epsNodes}
            draggable
            onMoveNode={(nodeId, newParentId) => moveMutation.mutate({ nodeId, newParentId })}
            renderNode={(node: EpsNodeResponse) => (
              <div className="group flex items-center justify-between gap-3 flex-1">
                <div className="flex-1">
                  {editingNodeId === node.id ? (
                    <div className="flex items-center gap-2">
                      <input
                        type="text"
                        value={editingName}
                        onChange={(e) => setEditingName(e.target.value)}
                        className="flex-1 rounded-md border border-border bg-surface-hover px-2 py-1 text-sm text-text-primary focus:border-accent focus:outline-none"
                        autoFocus
                        onKeyDown={(e) => { if (e.key === 'Enter') handleSaveEdit(node.id); if (e.key === 'Escape') setEditingNodeId(null); }}
                      />
                      <button
                        onClick={(e) => { e.stopPropagation(); handleSaveEdit(node.id); }}
                        disabled={updateMutation.isPending}
                        className="rounded p-1 text-success hover:bg-success/10 disabled:text-text-muted"
                      >
                        <Check size={16} />
                      </button>
                      <button
                        onClick={(e) => { e.stopPropagation(); setEditingNodeId(null); }}
                        className="rounded p-1 text-text-muted hover:bg-surface-active/50"
                      >
                        <X size={16} />
                      </button>
                    </div>
                  ) : (
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-accent">{node.code}</span>
                      <span className="text-text-secondary">{node.name}</span>
                    </div>
                  )}
                </div>
                {editingNodeId !== node.id && (
                  <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button
                      onClick={(e) => { e.stopPropagation(); handleAddChild(node.id, node.code, node.name); }}
                      className="rounded p-1 text-text-muted hover:bg-success/10 hover:text-success"
                      title="Add child node"
                    >
                      <FolderPlus size={16} />
                    </button>
                    <button
                      onClick={(e) => { e.stopPropagation(); handleStartEdit(node.id, node.name); }}
                      className="rounded p-1 text-text-muted hover:bg-accent-hover/10 hover:text-accent"
                      title="Edit"
                    >
                      <Edit2 size={16} />
                    </button>
                    <button
                      onClick={(e) => { e.stopPropagation(); handleDelete(node.id); }}
                      disabled={deleteMutation.isPending}
                      className="rounded p-1 text-text-muted hover:bg-danger/10 hover:text-danger disabled:text-text-muted"
                      title="Delete"
                    >
                      <Trash2 size={16} />
                    </button>
                  </div>
                )}
              </div>
            )}
          />
        </div>
      )}

      <ConfirmDialog
        open={deleteConfirm.open}
        title="Delete EPS Node"
        message="Are you sure you want to delete this EPS node? This action cannot be undone."
        confirmLabel="Delete"
        variant="danger"
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />
    </div>
  );
}
