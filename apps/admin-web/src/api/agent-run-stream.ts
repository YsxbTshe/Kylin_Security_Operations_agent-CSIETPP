/**
 * Agent Run 事件流（SSE）客户端，带轮询降级。
 *
 * 后端只有一个 SSE 端点：`GET /api/v2/agent-runs/{runId}/events`
 * （AgentRunController#events，produces=text/event-stream）。它的行为：
 *   - 连接时先按 `Last-Event-ID` 之后的 sequence 回放全部历史事件，再转直播；
 *   - 每帧 `id: <sequence>`、`event: <eventType>`、`data: {…}`；
 *   - run 进入终态（RUN_COMPLETED/FAILED/CANCELLED）后 complete() 关闭连接。
 *
 * ── 为什么用 fetch + ReadableStream 而不是原生 EventSource ────────────────
 * 1. 后端把 **事件类型当作 SSE event name** 发送。EventSource 只有 `onmessage`
 *    能收到匿名（`event: message`）帧，具名帧必须 addEventListener 逐个注册——
 *    这意味着后端任何新增事件类型都会被前端静默丢弃。fetch 解析器读的是原始
 *    字节流，任何 event name 都能拿到，不会漏。
 * 2. 鉴权。http.ts 是「内存态 Bearer + httpOnly 会话 Cookie」双读
 *    （后端 AuthService#resolveRequestToken：先看 Authorization 头，再看会话
 *    Cookie）。EventSource 不能自定义 header，只能靠 Cookie；而刷新页面后
 *    内存态 token 会丢失、此时只剩 Cookie 可用，反过来若 Cookie 因浏览器策略
 *    被拦掉就只剩 Bearer 可用。fetch 两者都能带：`credentials: 'include'`
 *    带 Cookie，`Authorization` 头带内存态 token，与 axios 拦截器口径一致。
 * 3. token 一律不进 URL query——签名会落进反向代理/Spring 的访问日志。
 *
 * 代价是断线重连要自己实现（见 backoff），以及需要 ReadableStream（本项目已经
 * 依赖 oklch / color-mix / three，本来就只面向现代浏览器）。
 */
import { authToken } from './http'

/** SSE 帧里 `data` 的形状，与 AgentRunController#sendIfNew 的 Map.of 一一对应。 */
export interface RunStreamEvent {
  eventId: string
  runId: string
  sequence: number
  eventType: string
  actorType: string
  actorId: string
  /** 已解析的 payload 对象（REST timeline 那边给的是 payloadJson 字符串）。 */
  payload: Record<string, unknown>
  eventHash: string
  createdAt: string
}

export type StreamPhase = 'connecting' | 'live' | 'polling' | 'closed'

export interface RunStreamHandlers {
  /** 收到一条事件（回放与直播都走这里，调用方按 sequence 去重/合并）。 */
  onEvent: (event: RunStreamEvent) => void
  /** 连接状态变化：驱动界面上的「实时 / 轮询」指示灯。 */
  onPhase?: (phase: StreamPhase) => void
  /** 降级为轮询时由调用方提供的兜底拉取（通常是重新拉一次 timeline）。 */
  onPollTick?: () => void
}

export interface RunStreamSubscription {
  close: () => void
}

const STREAM_URL = (runId: string) => `/api/v2/agent-runs/${encodeURIComponent(runId)}/events`
/** 连续失败到该次数后放弃 SSE，转轮询；之后仍会周期性尝试重新握手。 */
const MAX_STREAM_ATTEMPTS = 3
const POLL_INTERVAL_MS = 4000
/** 轮询降级后，每隔多久再试一次 SSE。 */
const STREAM_RETRY_MS = 20000
/** 服务端干净关闭但 run 未终态（多半是反代空闲超时）时的重连间隔。 */
const RESUME_DELAY_MS = 800
/** 见到这些事件说明 run 已终态，服务端会主动 complete，不再重连。 */
const TERMINAL_EVENTS = new Set(['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED'])

/**
 * 订阅一个 run 的事件流。返回的 subscription 必须在组件卸载或切换 run 时 close()，
 * 否则 fetch 的读循环会一直挂着。
 */
export function openRunEventStream(runId: string, handlers: RunStreamHandlers): RunStreamSubscription {
  let closed = false
  let attempts = 0
  let lastEventId = ''
  let terminalSeen = false
  let controller: AbortController | null = null
  let pollTimer: number | null = null
  let retryTimer: number | null = null
  let phase: StreamPhase = 'connecting'

  const setPhase = (next: StreamPhase) => {
    if (phase === next) return
    phase = next
    handlers.onPhase?.(next)
  }

  const stopPolling = () => {
    if (pollTimer != null) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
    if (retryTimer != null) {
      window.clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  /** SSE 不可用时的降级：定时重拉，同时后台继续尝试恢复 SSE。 */
  const startPolling = () => {
    if (closed || pollTimer != null) return
    setPhase('polling')
    pollTimer = window.setInterval(() => handlers.onPollTick?.(), POLL_INTERVAL_MS)
    retryTimer = window.setTimeout(() => {
      stopPolling()
      attempts = 0
      void connect()
    }, STREAM_RETRY_MS)
  }

  const dispatch = (frame: { id: string; data: string }) => {
    if (!frame.data) return
    let parsed: unknown
    try {
      parsed = JSON.parse(frame.data)
    } catch {
      return
    }
    if (!parsed || typeof parsed !== 'object') return
    const event = parsed as Partial<RunStreamEvent>
    if (typeof event.sequence !== 'number' || typeof event.eventType !== 'string') return
    if (frame.id) lastEventId = frame.id
    if (TERMINAL_EVENTS.has(event.eventType)) terminalSeen = true
    handlers.onEvent({
      eventId: String(event.eventId ?? ''),
      runId: String(event.runId ?? runId),
      sequence: event.sequence,
      eventType: event.eventType,
      actorType: String(event.actorType ?? ''),
      actorId: String(event.actorId ?? ''),
      payload: (event.payload && typeof event.payload === 'object'
        ? event.payload
        : {}) as Record<string, unknown>,
      eventHash: String(event.eventHash ?? ''),
      createdAt: String(event.createdAt ?? ''),
    })
  }

  async function connect(): Promise<void> {
    if (closed) return
    if (attempts === 0 && phase !== 'polling') setPhase('connecting')
    controller = new AbortController()
    const headers: Record<string, string> = { Accept: 'text/event-stream' }
    const token = authToken()
    if (token) headers.Authorization = `Bearer ${token}`
    // 断线重连时告诉服务端从哪个 sequence 之后续传，避免整段回放。
    if (lastEventId) headers['Last-Event-ID'] = lastEventId

    try {
      const response = await fetch(STREAM_URL(runId), {
        method: 'GET',
        headers,
        credentials: 'include',
        signal: controller.signal,
        cache: 'no-store',
      })
      if (!response.ok || !response.body) {
        // 401/403 不重试：重连也不会变好，直接降级让调用方的 REST 去暴露错误。
        if (response.status === 401 || response.status === 403) {
          startPolling()
          return
        }
        throw new Error(`SSE ${response.status}`)
      }
      attempts = 0
      stopPolling()
      setPhase('live')
      await readStream(response.body)
      if (closed) return
      if (terminalSeen) {
        // 服务端因 run 终态主动 complete：正常收尾，不再重连。
        setPhase('closed')
        return
      }
      // run 还没结束却断了（反代空闲超时、网络抖动）：带 Last-Event-ID 续传。
      // 不走 attempts 计数——这是干净关闭，不是失败，不该把人推去轮询。
      retryTimer = window.setTimeout(() => void connect(), RESUME_DELAY_MS)
    } catch (error) {
      if (closed || (error instanceof DOMException && error.name === 'AbortError')) return
      attempts += 1
      if (attempts >= MAX_STREAM_ATTEMPTS) {
        startPolling()
        return
      }
      // 退避重连：150ms → 600ms → …，只在 SSE 阶段生效。
      const delay = 150 * 4 ** (attempts - 1)
      retryTimer = window.setTimeout(() => void connect(), delay)
    }
  }

  async function readStream(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    // SSE 以空行分帧；chunk 边界可能落在帧中间，所以要缓冲到看见 \n\n 再切。
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary !== -1) {
        dispatch(parseFrame(buffer.slice(0, boundary)))
        buffer = buffer.slice(boundary + 2)
        boundary = buffer.indexOf('\n\n')
      }
      if (closed) {
        void reader.cancel()
        return
      }
    }
  }

  void connect()

  return {
    close() {
      closed = true
      stopPolling()
      controller?.abort()
      setPhase('closed')
    },
  }
}

/** 解析单个 SSE 帧：只取 id 与 data（event name 与 data.eventType 冗余，用后者）。 */
function parseFrame(raw: string): { id: string; data: string } {
  let id = ''
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    // 规范：冒号后可有一个空格，需剥掉。
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'id') id = value
    else if (field === 'data') dataLines.push(value)
  }
  return { id, data: dataLines.join('\n') }
}
