import { getAccessToken } from './token';
import { http, unwrap } from './http';
import type { AiMessageCitation } from '@/types/workspace';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';

export interface AskPayload {
  sessionId?: number | null;
  question: string;
  documentId?: number | null;
  provider?: string;
}

export interface AskMeta {
  sessionId: number;
  userMessageId: number;
  /** 本次流的 ID，用于「立即作答」按它停止继续检索。 */
  streamId: string;
}

export interface AskToolEvent {
  tool: string;
  summary: string;
}

export interface AskDone {
  messageId: number;
  citations: AiMessageCitation[];
  /** 风险分级：normal / warning。 */
  riskLevel?: string;
  /** 首轮生成的会话标题（仅首轮存在）。 */
  title?: string;
}

export interface AiFeedbackPayload {
  messageId: number;
  /** like/dislike/error/hallucination/missing_citation/escalate */
  feedbackType: string;
  rating?: number;
  feedbackContent?: string;
}

/** 提交 AI 回答反馈（纠错等）。 */
export function submitAiFeedback(payload: AiFeedbackPayload): Promise<number> {
  return unwrap<number>(http.post('/ai/feedback', payload));
}

/** 切换某条回答的点赞状态，返回切换后的点赞状态（true=已赞）。 */
export function toggleAiLike(messageId: number): Promise<boolean> {
  return unwrap<boolean>(http.post(`/ai/feedback/like/${messageId}`));
}

export interface AskHandlers {
  onMeta?: (meta: AskMeta) => void;
  onTool?: (event: AskToolEvent) => void;
  onDelta?: (text: string) => void;
  onDone?: (done: AskDone) => void;
  onError?: (message: string) => void;
  /**
   * 流在「已开始（收到过 meta/delta）但未收到 done」时异常断开：后端通常已在处理并极可能已落库，
   * 故区别于 onError——调用方应静默地按持久化结果收尾（重新拉取会话消息）而非报错。
   * 未提供时回退到 onError。
   */
  onAborted?: () => void;
}

export interface AskHandle {
  abort: () => void;
}

/**
 * 发起流式可溯源问答。基于 fetch + ReadableStream 解析 SSE
 * （axios 不便处理 POST + 自定义鉴权头的事件流）。
 * 注意：流式过程中不做 401 自动刷新，失败即回调 onError 由调用方提示重试。
 */
export function askStream(payload: AskPayload, handlers: AskHandlers): AskHandle {
  const controller = new AbortController();
  const token = getAccessToken();

  void (async () => {
    let doneReceived = false;
    // 是否已收到过 meta/delta：用于在异常断开时区分「真错误」与「已开始、后端极可能已完成」。
    let streamStarted = false;
    // 解析状态提到外层：连接异常关闭进 catch 时仍可 flush 残留 buffer，
    // 避免最后一个事件（尤其 done）因结尾空行与连接关闭在同一网络分包而丢失。
    let buffer = '';
    let eventName = 'message';
    let dataBuffer = '';

    const dispatch = () => {
      if (!dataBuffer) {
        eventName = 'message';
        return;
      }
      let parsed: unknown = dataBuffer;
      try {
        parsed = JSON.parse(dataBuffer);
      } catch {
        /* 非 JSON 时保留原始字符串 */
      }
      if (eventName === 'done') doneReceived = true;
      if (eventName === 'meta' || eventName === 'delta') streamStarted = true;
      handleEvent(eventName, parsed, handlers);
      eventName = 'message';
      dataBuffer = '';
    };

    // 处理 buffer 中所有完整的行；done=true 时连不完整的尾行也一并处理（流结束）。
    const drainBuffer = (flush: boolean) => {
      let newlineIdx;
      while ((newlineIdx = buffer.indexOf('\n')) >= 0) {
        let line = buffer.slice(0, newlineIdx);
        buffer = buffer.slice(newlineIdx + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        handleLine(line);
      }
      if (flush && buffer.length > 0) {
        let line = buffer;
        buffer = '';
        if (line.endsWith('\r')) line = line.slice(0, -1);
        handleLine(line);
      }
    };

    const handleLine = (line: string) => {
      if (line === '') {
        dispatch();
        return;
      }
      if (line.startsWith(':')) return; // 注释/心跳
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        let v = line.slice(5);
        if (v.startsWith(' ')) v = v.slice(1);
        dataBuffer += dataBuffer ? `\n${v}` : v;
      }
    };

    try {
      const response = await fetch(`${API_BASE_URL}/ai/messages/ask`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: JSON.stringify(payload),
        signal: controller.signal
      });

      if (!response.ok || !response.body) {
        handlers.onError?.(
          response.status === 401
            ? '登录状态已失效，请重新登录后重试。'
            : `请求失败（${response.status}）`
        );
        return;
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');

      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        drainBuffer(false);
      }
      // 正常结束：flush 解码器与残留 buffer，确保末尾事件（含未以空行收尾的 done）被处理。
      buffer += decoder.decode();
      drainBuffer(true);
      dispatch();
    } catch (error) {
      // 连接异常关闭（后端 SseEmitter.complete() 不发 terminating chunk，浏览器会抛 network error）：
      // 先 flush 残留 buffer 抢救末尾的 done 事件，再判断是否真的需要报错。
      try {
        drainBuffer(true);
        dispatch();
      } catch {
        /* 解析残留失败时忽略，按下方逻辑判断 */
      }
      if ((error as { name?: string })?.name !== 'AbortError' && !doneReceived) {
        // 已开始（meta/delta 到达过）但连接末段丢失 done：后端极可能已落库，交由调用方按持久化结果收尾。
        if (streamStarted && handlers.onAborted) {
          handlers.onAborted();
        } else {
          handlers.onError?.('连接中断，请重试。');
        }
      }
    }
  })();

  return { abort: () => controller.abort() };
}

/**
 * 请求当前在途问答「立即作答」：让模型停止继续调用检索工具、用已检索到的信息收尾。
 * 不中断 SSE 流，答案仍会经 delta 继续流入。幂等：streamId 已结束时后端静默成功。
 */
export function stopAsk(streamId: string): Promise<unknown> {
  return http.post('/ai/messages/stop', { streamId });
}

function handleEvent(name: string, data: unknown, handlers: AskHandlers) {
  switch (name) {
    case 'meta':
      handlers.onMeta?.(data as AskMeta);
      break;
    case 'tool':
      handlers.onTool?.(data as AskToolEvent);
      break;
    case 'delta':
      handlers.onDelta?.(typeof data === 'string' ? data : ((data as { text?: string })?.text ?? ''));
      break;
    case 'done':
      handlers.onDone?.(data as AskDone);
      break;
    case 'error':
      handlers.onError?.(
        typeof data === 'string' ? data : ((data as { message?: string })?.message ?? '服务异常')
      );
      break;
    default:
      break;
  }
}
