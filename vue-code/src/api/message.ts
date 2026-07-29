import { request } from '@/utils/request';
import type { ApiResponse } from '@/types';

// 消息信息
export interface ChatMessage {
  id: number;
  xianyuAccountId: number;
  lwp: string;
  pnmId: string;
  sid: string;
  contentType: number;
  msgContent: string;
  senderUserName: string;
  senderUserId: string;
  senderAppV: string;
  senderOsType: string;
  reminderUrl: string;
  xyGoodsId: string;
  completeMsg: string;
  messageTime: string | number;
  createTime: string;
  isNew?: boolean;
}

// 消息列表响应
export interface MessageListResponse {
  list: ChatMessage[];
  totalCount: number;
  totalPage: number;
  pageNum: number;
  pageSize: number;
}

export interface ConversationProfile {
  sid: string;
  avatar: string;
  nick: string;
}

// 获取消息列表
export function getMessageList(data: {
  xianyuAccountId: number;
  xyGoodsId?: string;
  pageNum?: number;
  pageSize?: number;
  filterCurrentAccount?: boolean; // 过滤当前账号消息
}, silent = false) {
  return request<MessageListResponse>({
    url: '/msg/list',
    method: 'POST',
    data,
    silent
  });
}

// 根据会话ID获取上下文消息
export function getContextMessages(data: {
  xianyuAccountId: number;
  sid: string;
  limit?: number;
  offset?: number;
}) {
  return request<ChatMessage[]>({
    url: '/msg/context',
    method: 'POST',
    data: {
      xianyuAccountId: data.xianyuAccountId,
      sid: data.sid,
      limit: data.limit || 20,
      offset: data.offset || 0
    }
  });
}

export function syncContextMessages(data: {
  xianyuAccountId: number;
  sid: string;
  maxMessages?: number;
}, silent = false) {
  return request<{ received: number; saved: number }>({
    url: '/msg/context/sync',
    method: 'POST',
    data,
    silent
  });
}

export function getConversationProfiles(data: {
  xianyuAccountId: number;
  sessionIds: string[];
}) {
  return request<ConversationProfile[]>({
    url: '/msg/conversation-profiles',
    method: 'POST',
    data
  });
}

// 发送消息
export function sendMessage(data: {
  xianyuAccountId: number;
  cid: string;
  toId: string;
  text: string;
  xyGoodsId?: string;
}) {
  return request<string>({
    url: '/websocket/sendMessage',
    method: 'POST',
    data
  });
}
