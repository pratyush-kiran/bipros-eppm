import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types";
import type { NotificationItem } from "../types/notification";

export const notificationApi = {
  list: (params?: { unreadOnly?: boolean; page?: number; size?: number }) => {
    const p = new URLSearchParams();
    if (params?.unreadOnly != null) p.set("unreadOnly", String(params.unreadOnly));
    if (params?.page != null) p.set("page", String(params.page));
    if (params?.size != null) p.set("size", String(params.size));
    const qs = p.toString() ? `?${p.toString()}` : "";
    return apiClient
      .get<ApiResponse<PagedResponse<NotificationItem>>>(`/v1/notifications${qs}`)
      .then((r) => r.data.data!);
  },

  unreadCount: () =>
    apiClient
      .get<ApiResponse<{ count: number }>>("/v1/notifications/unread-count")
      .then((r) => r.data.data!.count),

  markRead: (id: string) =>
    apiClient
      .patch<ApiResponse<boolean>>(`/v1/notifications/${id}/read`)
      .then((r) => r.data),

  markAllRead: () =>
    apiClient
      .post<ApiResponse<number>>("/v1/notifications/read-all")
      .then((r) => r.data),
};
