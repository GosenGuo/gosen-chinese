/** PackyAPI content tooling. Node 22+, no runtime dependencies, no embedded credentials. */
export const DEFAULT_BASE_URL = 'https://www.packyapi.ai';
export const DEFAULT_MODEL = 'gpt-6.1-sol';
export const DEFAULT_PROTOCOL = 'responses';

export function redactSecrets(value, secrets = []) {
  let text = String(value ?? '');
  for (const secret of secrets.filter(Boolean)) {
    text = text.split(String(secret)).join('[REDACTED]');
    text = text.split(encodeURIComponent(String(secret))).join('[REDACTED]');
  }
  return text
    .replace(/\bBearer\s+[^\s"',}]+/gi, 'Bearer [REDACTED]')
    .replace(/\bsk-[A-Za-z0-9_-]{8,}/g, '[REDACTED]')
    .replace(/(["']?(?:x-api-key|api[_-]?key|authorization)["']?\s*[:=]\s*["']?)[^\s"',}\]]+/gi, '$1[REDACTED]');
}

export class PackyError extends Error {
  constructor(code, message, { status = null, retryable = false } = {}) {
    super(message);
    this.name = 'PackyError';
    this.code = code;
    this.status = status;
    this.retryable = retryable;
  }
}

function endpoint(baseUrl, protocol) {
  let base;
  try { base = new URL(baseUrl); } catch { throw new PackyError('invalid_config', 'PACKY_API_BASE_URL 必须是有效的 HTTPS 地址。'); }
  if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash) {
    throw new PackyError('invalid_config', 'PACKY_API_BASE_URL 只允许无凭据、无查询参数的 HTTPS 地址。');
  }
  const suffix = protocol === 'anthropic' ? 'messages' : 'responses';
  const prefix = base.pathname.replace(/\/+$/, '');
  base.pathname = `${prefix.endsWith('/v1') ? prefix : `${prefix}/v1`}/${suffix}`;
  return base.toString();
}

export function parseJsonObject(text) {
  let candidate = String(text || '').trim();
  const fence = candidate.match(/^```(?:json)?\s*\n?([\s\S]*?)\n?```$/i);
  if (fence) candidate = fence[1].trim();
  let parsed;
  try { parsed = JSON.parse(candidate); } catch { throw new PackyError('invalid_json', '模型返回的内容不是完整有效的 JSON；未写入草稿。'); }
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new PackyError('invalid_json', '模型必须返回一个 JSON 对象。');
  return parsed;
}

function textFromResponse(response) {
  if (typeof response?.output_text === 'string' && response.output_text.trim()) return response.output_text;
  const texts = [];
  for (const item of response?.output || []) {
    for (const part of item?.content || []) {
      if (part?.type === 'output_text' && typeof part.text === 'string') texts.push(part.text);
    }
  }
  return texts.join('\n');
}

function tokenUsage(usage = {}) {
  const input = usage.input_tokens;
  const output = usage.output_tokens;
  const inputTokens = Number.isSafeInteger(input) && input >= 0 ? input : null;
  const outputTokens = Number.isSafeInteger(output) && output >= 0 ? output : null;
  return { inputTokens, outputTokens, totalTokens: Number.isSafeInteger(usage.total_tokens) && usage.total_tokens >= 0 ? usage.total_tokens : inputTokens !== null && outputTokens !== null ? inputTokens + outputTokens : null };
}

async function parseSse(response, safe, controller) {
  if (!response.body?.getReader) throw new PackyError('invalid_stream', '响应缺少可读取的 SSE 流。');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parts = new Map();
  let buffer = '';
  let eventLines = [];
  let completed = null;
  let bytes = 0;
  let finished = false;
  function consumeEvent(lines) {
    const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n');
    if (!data || data === '[DONE]') return;
    let event;
    try { event = JSON.parse(data); } catch { throw new PackyError('invalid_stream', 'SSE 流中包含无效 JSON。'); }
    const type = event.type || lines.find(line => line.startsWith('event:'))?.slice(6).trim();
    if (type === 'error' || type === 'response.failed') {
      const message = event.error?.message || event.response?.error?.message || event.message || '上游生成失败';
      throw new PackyError('provider_error', safe(message).slice(0, 500));
    }
    if (type === 'response.incomplete') throw new PackyError('incomplete_output', '生成未完成（可能达到输出限制）；未保存为有效草稿。');
    const key = `${event.output_index ?? 0}:${event.content_index ?? 0}`;
    if (type === 'response.output_text.delta') {
      if (typeof event.delta !== 'string') throw new PackyError('invalid_stream', '文本增量格式不正确。');
      parts.set(key, (parts.get(key) || '') + event.delta);
    }
    if (type === 'response.output_text.done' && typeof event.text === 'string') parts.set(key, event.text);
    if (type === 'response.completed') {
      completed = event.response;
      if (!completed || (completed.status && completed.status !== 'completed')) throw new PackyError('invalid_stream', '完成事件的状态不正确。');
      finished = true;
    }
  }
  try {
    while (!finished) {
      const { value, done } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      if (bytes > 5_000_000) { controller.abort(); throw new PackyError('response_too_large', '响应超过内容工具允许的大小。'); }
      buffer += decoder.decode(value, { stream: true });
      let newline;
      while ((newline = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newline).replace(/\r$/, '');
        buffer = buffer.slice(newline + 1);
        if (line === '') { consumeEvent(eventLines); eventLines = []; }
        else if (!line.startsWith(':')) eventLines.push(line);
        if (finished) break;
      }
    }
    if (!finished) {
      buffer += decoder.decode();
      if (buffer.trim()) eventLines.push(buffer.replace(/\r$/, ''));
      if (eventLines.length) consumeEvent(eventLines);
    }
    if (!completed) throw new PackyError('incomplete_stream', 'SSE 流在 response.completed 前结束；部分输出未保存。');
    const text = textFromResponse(completed) || [...parts.values()].join('\n');
    if (!text.trim()) throw new PackyError('empty_output', '接口返回完成状态，但没有正文；不将空输出视为成功。');
    return { text: safe(text), model: safe(completed.model || ''), responseId: safe(completed.id || ''), tokenUsage: tokenUsage(completed.usage), stopReason: 'completed' };
  } finally {
    // Some gateways keep the connection alive after their completion event.
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

export function createPackyClient(options = {}) {
  const env = options.env || process.env;
  const apiKey = options.apiKey ?? env.PACKY_API_KEY;
  if (!apiKey || !String(apiKey).trim()) throw new PackyError('missing_key', '请通过 PACKY_API_KEY 环境变量提供密钥。');
  const key = String(apiKey).trim();
  const protocol = options.protocol ?? env.PACKY_API_PROTOCOL ?? DEFAULT_PROTOCOL;
  if (!['responses', 'anthropic'].includes(protocol)) throw new PackyError('invalid_config', 'PACKY_API_PROTOCOL 只支持 responses 或 anthropic。');
  const model = options.model ?? env.PACKY_API_MODEL ?? DEFAULT_MODEL;
  if (typeof model !== 'string' || !model.trim() || model.length > 200) throw new PackyError('invalid_config', 'PACKY_API_MODEL 必须是有效的模型名称。');
  const url = endpoint(options.baseUrl ?? env.PACKY_API_BASE_URL ?? DEFAULT_BASE_URL, protocol);
  const timeoutMs = options.timeoutMs ?? 180_000;
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 600_000) throw new PackyError('invalid_config', 'timeoutMs 必须在 1 到 600000 毫秒之间。');
  const fetchImpl = options.fetchImpl ?? globalThis.fetch;
  const streaming = options.stream ?? env.PACKY_API_STREAM !== 'false';
  const safe = value => redactSecrets(value, [key]);

  async function generate({ prompt, system = '', maxTokens = 4096, stream = streaming } = {}) {
    if (typeof prompt !== 'string' || !prompt.trim()) throw new PackyError('invalid_input', 'prompt 不能为空。');
    if (!Number.isSafeInteger(maxTokens) || maxTokens < 1 || maxTokens > 32768) throw new PackyError('invalid_input', 'maxTokens 必须是 1 到 32768 之间的整数。');
    const isAnthropic = protocol === 'anthropic';
    const headers = { 'content-type': 'application/json' };
    let body;
    if (isAnthropic) {
      headers['x-api-key'] = key;
      headers['anthropic-version'] = '2023-06-01';
      body = { model, max_tokens: maxTokens, messages: [{ role: 'user', content: prompt }] };
      if (system) body.system = system;
    } else {
      headers.authorization = `Bearer ${key}`;
      body = { model, input: [{ role: 'user', content: prompt }], max_output_tokens: maxTokens, stream: !!stream, store: false };
      if (system) body.instructions = system;
    }
    const controller = new AbortController();
    let timeout;
    const timedOut = new Promise((_, reject) => {
      timeout = setTimeout(() => { controller.abort(); reject(new PackyError('timeout', '接口连接或生成超时；没有自动重试或覆盖内容。', { retryable: true })); }, timeoutMs);
    });
    const request = async () => {
      const response = await fetchImpl(url, { method: 'POST', headers, body: JSON.stringify(body), signal: controller.signal, redirect: 'error' });
      if (!response.ok) {
        const errorBody = await response.text();
        throw new PackyError('http_error', `接口返回 HTTP ${response.status}：${safe(errorBody).slice(0, 500)}`, { status: response.status, retryable: response.status === 429 || response.status >= 500 });
      }
      if (!isAnthropic && stream && response.headers.get('content-type')?.toLowerCase().includes('text/event-stream')) return parseSse(response, safe, controller);
      const raw = await response.text();
      if (raw.length > 5_000_000) throw new PackyError('response_too_large', '接口响应超过允许大小。');
      let result;
      try { result = JSON.parse(raw); } catch { throw new PackyError('invalid_response', '接口未返回有效 JSON 或 SSE；没有生成草稿。'); }
      if (result.error) throw new PackyError('provider_error', safe(result.error.message || result.error).slice(0, 500));
      if (isAnthropic) {
        if (result.stop_reason === 'max_tokens') throw new PackyError('incomplete_output', '生成达到输出限制；请调整输出预算后重新生成。');
        if (!Array.isArray(result.content)) throw new PackyError('invalid_response', 'Anthropic响应缺少content数组。');
        const text = (result.content || []).filter(part => part.type === 'text').map(part => part.text || '').join('\n');
        if (!text.trim()) throw new PackyError('empty_output', '接口没有返回可用正文。');
        return { text: safe(text), model: safe(result.model || model), responseId: safe(result.id || ''), tokenUsage: tokenUsage(result.usage), stopReason: result.stop_reason || null };
      }
      if (result.status && result.status !== 'completed') throw new PackyError('incomplete_output', `接口生成状态为 ${safe(result.status)}；未保存部分内容。`);
      const text = textFromResponse(result);
      if (!text.trim()) throw new PackyError('empty_output', '接口返回 HTTP 200 但正文为空；请使用默认 SSE 流式协议。');
      return { text: safe(text), model: safe(result.model || model), responseId: safe(result.id || ''), tokenUsage: tokenUsage(result.usage), stopReason: result.status || null };
    };
    try {
      const result = await Promise.race([request(), timedOut]);
      return { ...result, model: result.model || safe(model), protocol, generatedAt: new Date().toISOString() };
    } catch (error) {
      if (error instanceof PackyError) throw error;
      throw new PackyError('network_error', `接口请求失败：${safe(error?.message || '未知连接错误').slice(0, 500)}`, { retryable: true });
    } finally { clearTimeout(timeout); }
  }
  return {
    generate,
    async generateJson(args) { const result = await generate(args); return { ...result, data: parseJsonObject(result.text) }; },
    config: { baseUrl: options.baseUrl ?? env.PACKY_API_BASE_URL ?? DEFAULT_BASE_URL, model, protocol, stream: !!streaming }
  };
}
