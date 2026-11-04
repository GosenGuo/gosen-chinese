import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { createPackyClient, parseJsonObject, redactSecrets } from './packy-client.mjs';
import { run, validateClassicSupplement, validateEssay, writeDraftAtomic } from './generate-content.mjs';

const mockKey = 'sk-test-not-a-real-secret-123456789';
const result = { id: 'response-mock', model: 'gpt-6.1-sol', status: 'completed', output: [], usage: { input_tokens: 10, output_tokens: 5, total_tokens: 15 } };
const dataLine = data => `data: ${JSON.stringify(data)}\r\n\r\n`;
function sse(events, chunkSize = 7) {
  const bytes = new TextEncoder().encode(events.map(dataLine).join(''));
  let offset = 0;
  return new Response(new ReadableStream({
    pull(controller) {
      if (offset >= bytes.length) { controller.close(); return; }
      controller.enqueue(bytes.slice(offset, offset + chunkSize));
      offset += chunkSize;
    }
  }), { headers: { 'content-type': 'text/event-stream; charset=utf-8' } });
}
const mockClient = fetchImpl => createPackyClient({ apiKey: mockKey, fetchImpl });
const jsonResponse = data => new Response(JSON.stringify(data), { headers: { 'content-type': 'application/json' } });
async function removeTempDirectory(directory) {
  const target = path.resolve(directory);
  assert.equal(path.dirname(target), path.resolve(os.tmpdir()));
  assert(path.basename(target).startsWith('gosen-'));
  await fs.rm(target, { recursive: true, force: true });
}

test('Responses defaults use SSE, Bearer header and correct endpoint/body', async () => {
  let calls = 0;
  const api = mockClient(async (url, options) => {
    calls++;
    assert.equal(url, 'https://www.packyapi.ai/v1/responses');
    assert.equal(options.method, 'POST');
    assert.equal(options.redirect, 'error');
    assert.equal(options.headers.authorization, `Bearer ${mockKey}`);
    assert.equal(options.headers['x-api-key'], undefined);
    const body = JSON.parse(options.body);
    assert.equal(body.model, 'gpt-6.1-sol');
    assert.equal(body.stream, true);
    assert.equal(body.store, false);
    assert.equal(body.max_output_tokens, 128);
    assert.equal(body.instructions, 'system text');
    assert.equal(body.input[0].content, 'hello');
    return sse([
      { type: 'response.created', response: { id: 'response-mock' } },
      { type: 'response.output_text.delta', output_index: 0, content_index: 0, delta: '接口已' },
      { type: 'response.output_text.delta', output_index: 0, content_index: 0, delta: '连接' },
      { type: 'response.completed', response: result }
    ]);
  });
  const value = await api.generate({ prompt: 'hello', system: 'system text', maxTokens: 128 });
  assert.equal(value.text, '接口已连接');
  assert.equal(value.protocol, 'responses');
  assert.deepEqual(value.tokenUsage, { inputTokens: 10, outputTokens: 5, totalTokens: 15 });
  assert.equal(calls, 1);
});

test('SSE done/final events do not duplicate accumulated text', async () => {
  const completed = { ...result, output: [{ type: 'message', content: [{ type: 'output_text', text: '完整正文' }] }] };
  const api = mockClient(async () => sse([
    { type: 'response.output_text.delta', delta: '完整' },
    { type: 'response.output_text.delta', delta: '正文' },
    { type: 'response.output_text.done', text: '完整正文' },
    { type: 'response.completed', response: completed }
  ], 1));
  assert.equal((await api.generate({ prompt: 'x' })).text, '完整正文');
});

test('SSE rejects premature EOF rather than accepting partial content', async () => {
  const api = mockClient(async () => sse([{ type: 'response.output_text.delta', delta: '{"notes":' }]));
  await assert.rejects(api.generate({ prompt: 'x' }), error => error.code === 'incomplete_stream');
});

test('SSE rejects an empty completed stream', async () => {
  const api = mockClient(async () => sse([{ type: 'response.completed', response: result }]));
  await assert.rejects(api.generate({ prompt: 'x' }), error => error.code === 'empty_output');
});

test('SSE provider errors redact credentials', async () => {
  const api = mockClient(async () => sse([{ type: 'error', message: `Authorization: Bearer ${mockKey}` }]));
  await assert.rejects(api.generate({ prompt: 'x' }), error => error.code === 'provider_error' && !error.message.includes(mockKey));
});

test('SSE incomplete event is not a usable draft', async () => {
  const api = mockClient(async () => sse([{ type: 'response.output_text.delta', delta: '部分' }, { type: 'response.incomplete', response: { status: 'incomplete' } }]));
  await assert.rejects(api.generate({ prompt: 'x' }), error => error.code === 'incomplete_output');
});

test('malformed SSE event is rejected', async () => {
  const api = mockClient(async () => new Response('data: {broken}\n\n', { headers: { 'content-type': 'text/event-stream' } }));
  await assert.rejects(api.generate({ prompt: 'x' }), error => error.code === 'invalid_stream');
});

test('non-streaming HTTP 200 with empty output is rejected', async () => {
  const api = createPackyClient({ apiKey: mockKey, stream: false, fetchImpl: async () => jsonResponse(result) });
  await assert.rejects(api.generate({ prompt: 'x' }), error => error.code === 'empty_output');
});

test('valid non-streaming text and token usage are normalized', async () => {
  const api = createPackyClient({ apiKey: mockKey, baseUrl: 'https://example.com/v1/', stream: false, fetchImpl: async (url) => {
    assert.equal(url, 'https://example.com/v1/responses');
    return jsonResponse({ ...result, output: [{ content: [{ type: 'output_text', text: '正文' }] }] });
  } });
  assert.equal((await api.generate({ prompt: 'x' })).text, '正文');
});

test('HTTP errors expose status without exposing secret or automatically retrying', async () => {
  let calls = 0;
  const api = mockClient(async () => {
    calls++;
    return new Response(JSON.stringify({ error: { message: `bad token ${mockKey}` } }), { status: 429 });
  });
  await assert.rejects(api.generate({ prompt: 'x' }), error => error.status === 429 && error.retryable && !error.message.includes(mockKey));
  assert.equal(calls, 1);
});

test('full-request timeout handles nonresponsive transports', async () => {
  const api = createPackyClient({ apiKey: mockKey, timeoutMs: 10, fetchImpl: async () => new Promise(() => {}) });
  await assert.rejects(api.generate({ prompt: 'x' }), error => error.code === 'timeout');
});

test('Anthropic protocol is explicit, native and does not impersonate CLI clients', async () => {
  const api = createPackyClient({ apiKey: mockKey, protocol: 'anthropic', model: 'claude-example', fetchImpl: async (url, options) => {
    assert.equal(url, 'https://www.packyapi.ai/v1/messages');
    assert.equal(options.headers['x-api-key'], mockKey);
    assert.equal(options.headers['anthropic-version'], '2023-06-01');
    assert.equal(options.headers.authorization, undefined);
    assert.equal(options.headers['user-agent'], undefined);
    assert.equal(JSON.parse(options.body).max_tokens, 32);
    return jsonResponse({ content: [{ type: 'text', text: '示例' }], usage: { input_tokens: 2, output_tokens: 2 }, stop_reason: 'end_turn' });
  } });
  assert.equal((await api.generate({ prompt: 'x', maxTokens: 32 })).text, '示例');
});

test('configuration and JSON parsing reject malformed input', () => {
  assert.throws(() => createPackyClient({ env: {} }), error => error.code === 'missing_key');
  assert.throws(() => createPackyClient({ apiKey: mockKey, baseUrl: 'http://example.com' }), error => error.code === 'invalid_config');
  assert.throws(() => createPackyClient({ apiKey: mockKey, baseUrl: 'https://user:secret@example.com/' }), error => error.code === 'invalid_config');
  assert.throws(() => parseJsonObject('[1]'), error => error.code === 'invalid_json');
  assert.throws(() => parseJsonObject('{broken}'), error => error.code === 'invalid_json');
  assert.deepEqual(parseJsonObject('```json\n{"ok":true}\n```'), { ok: true });
  assert(!redactSecrets(`key=${mockKey}`, [mockKey]).includes(mockKey));
});

const classic = { id: 'example', body: '君子好学。君子慎言。' };
const supplement = { notes: [{ quote: '君子', occurrence: 1, meaning: '有德行的人', sentence: '君子慎言。' }], translations: [{ quote: '君子好学。', translation: '有德行的人爱好学习。' }, { quote: '君子慎言。', translation: '有德行的人说话谨慎。' }] };
test('repeated-word notes are tied to their precise occurrence', () => {
  assert.equal(validateClassicSupplement(supplement, classic).coverage.complete, true);
  assert.throws(() => validateClassicSupplement({ ...supplement, notes: [{ ...supplement.notes[0], occurrence: 0 }] }, classic), error => error.code === 'invalid_content');
  assert.throws(() => validateClassicSupplement({ ...supplement, translations: [{ quote: '原文没有', translation: 'x' }] }, classic), error => error.code === 'invalid_content');
});

test('essay validation enforces actual length and genuine AI labeling', () => {
  const data = { title: '测试', prompt: '这是完整自拟材料。'.repeat(10), requirements: '写议论文，不少于800字。', body: '学习需要思考与实践。'.repeat(100), outline: Array.from({ length: 4 }, (_, i) => ({ title: `第${i}项`, text: '解析内容' })), exercise: '写一段仿写。' };
  assert.equal(validateEssay(data).contentType, 'AI示范作文');
  assert.throws(() => validateEssay({ ...data, body: '太短' }), error => error.code === 'invalid_content');
  assert.throws(() => validateEssay({ ...data, score: 60 }), error => error.code === 'invalid_content');
});

test('atomic drafts refuse to overwrite an existing file', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'gosen-draft-'));
  try {
    const destination = path.join(dir, 'draft.json');
    await writeDraftAtomic(destination, { first: true });
    await assert.rejects(writeDraftAtomic(destination, { second: true }), { code: 'EEXIST' });
    assert.deepEqual(JSON.parse(await fs.readFile(destination, 'utf8')), { first: true });
    assert.deepEqual(await fs.readdir(dir), ['draft.json']);
  } finally { await removeTempDirectory(dir); }
});

test('CLI enrichment writes only a review-required draft and retains source', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'gosen-generator-'));
  try {
    const assetDir = path.join(dir, 'app/src/main/assets');
    await fs.mkdir(assetDir, { recursive: true });
    const source = JSON.stringify({ items: [classic] });
    await fs.writeFile(path.join(assetDir, 'classics.json'), source);
    const client = { generateJson: async () => ({ data: supplement, model: 'mock', protocol: 'responses', generatedAt: '2026-11-04T00:00:00.000Z', tokenUsage: { totalTokens: 1 } }) };
    const summary = await run(['enrich-classic', '--id', 'example', '--out', 'generated/sample.json'], { client, projectRoot: dir, log: () => {} });
    const draft = JSON.parse(await fs.readFile(summary.draftPath, 'utf8'));
    assert.equal(draft.status, 'draft');
    assert.equal(draft.reviewRequired, true);
    assert.equal(draft.metadata.sourceHash.length, 64);
    assert.equal(draft.content.coverage.complete, true);
    assert.equal(await fs.readFile(path.join(assetDir, 'classics.json'), 'utf8'), source);
  } finally { await removeTempDirectory(dir); }
});

test('unsafe output paths are rejected before any API request', async () => {
  let calls = 0;
  const client = { generateJson: async () => { calls++; throw new Error('should not call'); } };
  await assert.rejects(run(['generate-essay', '--out', 'app/src/main/assets/essays.json'], { client, projectRoot: process.cwd(), log: () => {} }), error => error.code === 'invalid_input');
  assert.equal(calls, 0);
});
