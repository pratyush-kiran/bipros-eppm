// Notification domain types — mirror the backend NotificationResponse DTO.

export interface NotificationItem {
  id: string;
  type: string;
  title: string;
  body: string;
  linkUrl?: string | null;
  projectId?: string | null;
  relatedEntityId?: string | null;
  createdAt: string;
  readAt?: string | null;
}
