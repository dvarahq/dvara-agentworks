// The Web Console timeline script (issue #36) run against a minimal DOM and a scripted EventSource, by
// src/test/python/test_console_timeline.py. Usage: node console-timeline.test.mjs <path to console.js>
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(process.argv[2], 'utf8');
let failures = 0;
const check = (condition, message) => {
  if (!condition) failures++;
  console.log(condition ? 'ok  ' : 'FAIL', message);
};

function element(tag) {
  return {
    tag, children: [], attributes: {}, className: '', textContent: '', listeners: {},
    get firstChild() { return this.children[0] || null; },
    appendChild(child) { this.children.push(child); return child; },
    insertBefore(child, before) {
      const index = this.children.indexOf(before);
      this.children.splice(index < 0 ? this.children.length : index, 0, child);
      return child;
    },
    removeChild(child) { this.children.splice(this.children.indexOf(child), 1); return child; },
    querySelectorAll(selector) {
      const [tagName, className] = selector.split('.');
      return this.children.filter((child) => child.tag === tagName && child.className.split(' ').includes(className));
    },
    setAttribute(name, value) { this.attributes[name] = String(value); },
    getAttribute(name) { return name in this.attributes ? this.attributes[name] : null; },
    hasAttribute(name) { return name in this.attributes; },
    querySelector(selector) {
      const name = selector.slice(1, -1);
      return this.children.find((child) => name in child.attributes) || null;
    },
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); },
    dispatchEvent(event) { (this.listeners[event.type] || []).forEach((listener) => listener(event)); },
    set innerHTML(value) { throw new Error('innerHTML must never be used'); },
  };
}

async function scenario(name, run, options = {}) {
  const timeline = element('ol');
  Object.assign(timeline.attributes, {
    'data-run-stream': '', 'data-stream-url': '/api/v1/runs/r1/stream', 'data-run-url': '/api/v1/runs/r1',
    'data-cursor': '2', 'data-event-types': 'RUN_CREATED,RUN_QUEUED,MODEL_STREAM_CHUNK,RUN_SUCCEEDED,RUN_PAUSED',
    'data-terminal': options.terminal ? 'true' : 'false', 'data-complete': options.complete ? 'true' : 'false',
    'data-terminal-lifecycles': 'SUCCEEDED,FAILED,CANCELLED',
  });
  const status = element('p');
  const body = element('body');
  const events = [];
  ['run-changed', 'run-ended'].forEach((type) => body.addEventListener(type, () => events.push(type)));
  const sources = [];
  const windowListeners = {};
  class FakeEventSource {
    static CLOSED = 2;
    constructor(url) { this.url = url; this.readyState = 0; this.listeners = {}; sources.push(this); }
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
    emit(type, data) { (this.listeners[type] || []).forEach((listener) => listener({ data: JSON.stringify(data) })); }
    close() { this.readyState = 2; }
  }
  const clock = { now: 1_000_000 };
  const context = {
    Date: { now: () => clock.now },
    window: { crypto: { randomUUID: () => 'id-' + Math.random().toString(16).slice(2) }, EventSource: FakeEventSource,
      addEventListener: (type, listener) => (windowListeners[type] ||= []).push(listener) },
    EventSource: FakeEventSource,
    document: {
      readyState: 'complete', body,
      getElementById: (id) => (id === 'timeline' ? timeline : id === 'stream-status' ? status : null),
      createElement: element, addEventListener() {},
    },
    CustomEvent: class { constructor(type) { this.type = type; } },
    fetch: () => Promise.resolve(options.runStatus && options.runStatus !== 200
      ? { ok: false, status: options.runStatus, json: () => Promise.resolve({}) }
      : { ok: true, status: 200,
        json: () => Promise.resolve(options.runView || { lifecycle: 'ACTIVE', eventSequence: 9 }) }),
    JSON, Number, String, Array, Math, Uint8Array, encodeURIComponent, console,
  };
  context.window.document = context.document;
  vm.createContext(context);
  vm.runInContext(source, context);
  console.log('--', name);
  await run({ timeline, status, sources, body, events, windowListeners, clock });
}

const XSS = '<script>alert(1)</script>';
const settle = () => new Promise((resolve) => setTimeout(resolve, 10));
const chunk = (sequence, payload = {}) => ({ sequence, type: 'MODEL_STREAM_CHUNK', category: 'MODEL', payload });

await scenario('events render as text, in order, without duplicates', ({ timeline, sources, events }) => {
  check(sources.length === 1, 'one EventSource opened');
  check(/cursor=2&client=id-/.test(sources[0].url), 'opened after the rendered cursor with a client id: ' + sources[0].url);
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(3, { text: XSS }));
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(3));
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(2));
  sources[0].emit('RUN_PAUSED', { sequence: 4, type: 'RUN_PAUSED', category: 'LIFECYCLE', payload: {} });
  check(timeline.children.length === 2, 'duplicate and stale sequences are dropped');
  check(timeline.children[0].children[1].children[1].textContent.includes(XSS), 'the payload is written as text');
  check(events.filter((type) => type === 'run-changed').length === 2, 'each new event signals run-changed');
});

await scenario('stream-end closes and signals the end', ({ sources, status, events }) => {
  sources[0].emit('stream-end', { lifecycle: 'SUCCEEDED' });
  check(sources[0].readyState === 2, 'closed on stream-end');
  check(status.textContent.includes('SUCCEEDED'), 'the status says the run ended: ' + status.textContent);
  check(events.includes('run-ended'), 'run-ended refreshes the summary and the actions');
});

await scenario('stop codes close; reconnect codes keep the native reconnect', ({ sources, status }) => {
  sources[0].emit('stream-error', { code: 'STREAM_UNAVAILABLE' });
  check(sources[0].readyState !== 2, 'STREAM_UNAVAILABLE leaves the EventSource to reconnect');
  sources[0].emit('reconnect', { reason: 'MAX_DURATION' });
  (sources[0].listeners.error || []).forEach((listener) => listener({}));
  check(status.textContent.includes('MAX_DURATION'), 'a connection error keeps the reconnect reason: ' + status.textContent);
  check(sources[0].readyState !== 2, 'reconnect leaves the EventSource to reconnect');
  sources[0].emit('stream-error', { code: 'STREAM_SUPERSEDED' });
  check(sources[0].readyState === 2, 'STREAM_SUPERSEDED closes');
  check(status.textContent.includes('resumed elsewhere'), 'the status explains supersession');
});

await scenario('resync codes re-read the run and follow again', async ({ sources, timeline }) => {
  sources[0].emit('stream-error', { code: 'EVENT_HISTORY_EXPIRED' });
  await settle();
  check(sources[0].readyState === 2, 'the old EventSource is closed');
  check(sources.length === 2, 'a new EventSource follows from the run\'s sequence');
  check(/cursor=9&client=id-/.test(sources[1].url), 'a new cursor and a new client id: ' + sources[1].url);
  check(sources[1].url.split('client=')[1] !== sources[0].url.split('client=')[1], 'a new EventSource gets a new id');
  check(timeline.children.some((child) => String(child.textContent).includes('not shown')), 'the gap is marked');
}, { runView: { lifecycle: 'ACTIVE', eventSequence: 9 } });

await scenario('a resync follows from the run and never shows an event twice', async ({ sources, timeline }) => {
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(5));
  sources[0].emit('stream-error', { code: 'EVENT_SEQUENCE_AHEAD' });
  await settle();
  check(/cursor=3&/.test(sources[1].url), 'the new stream follows from the run\'s sequence: ' + sources[1].url);
  sources[1].emit('MODEL_STREAM_CHUNK', chunk(4));
  sources[1].emit('MODEL_STREAM_CHUNK', chunk(5));
  sources[1].emit('MODEL_STREAM_CHUNK', chunk(6));
  const shown = timeline.querySelectorAll('li.console-event').map((item) => item.getAttribute('data-sequence'));
  check(JSON.stringify(shown) === '["5","6"]', 'events already shown are not shown again: ' + JSON.stringify(shown));
}, { runView: { lifecycle: 'ACTIVE', eventSequence: 3 } });

const refuse = (source) => {
  source.readyState = 2;
  (source.listeners.error || []).forEach((listener) => listener({}));
};

await scenario('a reconnect refused with 404, 409, or 410 re-reads the run and follows again', async ({ sources, timeline }) => {
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(3));
  refuse(sources[0]);
  await settle();
  check(sources.length === 2 && /cursor=3&/.test(sources[1].url),
    'first it follows on from the last event shown, skipping nothing: ' + (sources[1] && sources[1].url));
  refuse(sources[1]);
  await settle();
  check(sources.length === 3 && /cursor=9&/.test(sources[2].url),
    'refused again, it follows from the run\'s sequence: ' + (sources[2] && sources[2].url));
  check(timeline.children.some((child) => String(child.textContent).includes('Events 4–9 are not shown')),
    'and marks the events it cannot show');
  refuse(sources[2]);
  await settle();
  refuse(sources[3]);
  await settle();
  check(sources.length === 4, 'it gives up after repeated refusals without an event');
}, { runView: { lifecycle: 'ACTIVE', eventSequence: 9 } });

await scenario('#195: a connection that opens and is refused again and again gives up', async ({ sources, status }) => {
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(3));
  for (let attempt = 0; attempt < 6; attempt++) {
    const current = sources[sources.length - 1];
    current.emit('open', {});
    refuse(current);
    await settle();
  }
  check(sources.length === 4, 'open-then-refused cycles without an event are bounded: ' + sources.length);
  check(status.textContent.includes('unavailable'), 'and the page says so: ' + status.textContent);
}, { runView: { lifecycle: 'ACTIVE', eventSequence: 9 } });

await scenario('#200: time spent reconnecting is not healthy connection time', async ({ sources, status, clock }) => {
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(3));
  for (let attempt = 0; attempt < 6; attempt++) {
    const current = sources[sources.length - 1];
    current.emit('open', {});
    clock.now += 1000;
    // The connection drops; the browser keeps reconnecting for a minute before the server refuses it.
    (current.listeners.error || []).forEach((listener) => listener({}));
    clock.now += 60000;
    refuse(current);
    await settle();
  }
  check(sources.length === 4, 'short connections separated by long reconnects still give up: ' + sources.length);
  check(status.textContent.includes('unavailable'), 'and the page says so: ' + status.textContent);
}, { runView: { lifecycle: 'ACTIVE', eventSequence: 9 } });

await scenario('#195: an event ends a run of failed cycles', async ({ sources, status }) => {
  for (let attempt = 0; attempt < 6; attempt++) {
    const current = sources[sources.length - 1];
    current.emit('open', {});
    current.emit('MODEL_STREAM_CHUNK', chunk(3 + attempt));
    refuse(current);
    await settle();
  }
  check(sources.length === 7, 'every refusal after an event follows again: ' + sources.length);
  check(!status.textContent.includes('unavailable'), 'it never gives up: ' + status.textContent);
}, { runView: { lifecycle: 'ACTIVE', eventSequence: 9 } });

await scenario('#195: refusals separated by healthy idle periods never add up', async ({ sources, timeline, status, clock }) => {
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(3));
  for (let attempt = 0; attempt < 6; attempt++) {
    const current = sources[sources.length - 1];
    current.emit('open', {});
    clock.now += 60000;
    refuse(current);
    await settle();
  }
  check(sources.length === 7, 'every refusal after an open connection follows again: ' + sources.length);
  check(sources.slice(1).every((source) => /cursor=3&/.test(source.url)),
    'each follows on from the last event shown, skipping nothing: ' + sources.map((source) => source.url).join(' '));
  check(!timeline.children.some((child) => String(child.textContent).includes('not shown')), 'no gap is marked');
  check(!status.textContent.includes('unavailable'), 'it never gives up: ' + status.textContent);
}, { runView: { lifecycle: 'ACTIVE', eventSequence: 9 } });

await scenario('a run that cannot be read after a refusal says so', async ({ sources, status }) => {
  refuse(sources[0]);
  await settle();
  check(status.textContent.includes('can no longer be read (404)'), 'the status says why: ' + status.textContent);
}, { runStatus: 404 });

await scenario('an ended run whose events are all on the page opens no stream', ({ sources, status }) => {
  check(sources.length === 0, 'no EventSource is opened');
  check(status.textContent === 'The run has ended.', 'the status says the run ended: ' + status.textContent);
}, { terminal: true, complete: true });

await scenario('an ended run with events left streams them without announcing the end again', ({ sources, status, events }) => {
  check(sources.length === 1, 'one stream fetches the remaining events');
  check(status.textContent === '', 'it does not claim the run is live: ' + status.textContent);
  sources[0].emit('stream-end', { lifecycle: 'SUCCEEDED' });
  check(!events.includes('run-ended'), 'the page already rendered the end: no run-ended refresh');
}, { terminal: true });

await scenario('leaving the page closes its stream', ({ sources, windowListeners }) => {
  (windowListeners.pagehide || []).forEach((listener) => listener({}));
  check(sources[0].readyState === 2, 'pagehide closes the EventSource');
  (windowListeners.pageshow || []).forEach((listener) => listener({ persisted: false }));
  check(sources.length === 1, 'a page that was not kept in the back/forward cache opens nothing more');
});

await scenario('#195: a page restored from the back/forward cache follows the run again', ({ sources, windowListeners }) => {
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(3));
  (windowListeners.pagehide || []).forEach((listener) => listener({ persisted: true }));
  check(sources[0].readyState === 2, 'pagehide closes the EventSource');
  (windowListeners.pageshow || []).forEach((listener) => listener({ persisted: true }));
  check(sources.length === 2, 'pageshow from the cache opens a new EventSource');
  check(/cursor=3&/.test(sources[1] && sources[1].url), 'after the last event shown: ' + (sources[1] && sources[1].url));
  (windowListeners.pageshow || []).forEach((listener) => listener({ persisted: true }));
  check(sources.length === 2, 'a second pageshow opens nothing more');
});

await scenario('#195: a page hidden while it re-read the run follows again when restored', async ({ sources, windowListeners }) => {
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(3));
  refuse(sources[0]);
  (windowListeners.pagehide || []).forEach((listener) => listener({ persisted: true }));
  await settle();
  check(sources.length === 1, 'the re-read that answers while the page is hidden opens nothing');
  (windowListeners.pageshow || []).forEach((listener) => listener({ persisted: true }));
  check(sources.length === 2 && /cursor=3&/.test(sources[1].url),
    'pageshow follows again after the last event shown: ' + (sources[1] && sources[1].url));
}, { runView: { lifecycle: 'ACTIVE', eventSequence: 9 } });

await scenario('#195: a page restored before its re-read answers opens exactly one stream', async ({ sources, windowListeners }) => {
  sources[0].emit('MODEL_STREAM_CHUNK', chunk(3));
  refuse(sources[0]);
  (windowListeners.pagehide || []).forEach((listener) => listener({ persisted: true }));
  (windowListeners.pageshow || []).forEach((listener) => listener({ persisted: true }));
  await settle();
  check(sources.length === 2, 'the re-read begun before the page was hidden opens nothing: ' + sources.length);
  check(sources[1].readyState !== 2, 'the stream opened on restore stays open');
}, { runView: { lifecycle: 'ACTIVE', eventSequence: 9 } });

await scenario('#195: a page whose stream had stopped stays stopped when restored', ({ sources, windowListeners }) => {
  sources[0].emit('stream-error', { code: 'STREAM_SUPERSEDED' });
  (windowListeners.pagehide || []).forEach((listener) => listener({ persisted: true }));
  (windowListeners.pageshow || []).forEach((listener) => listener({ persisted: true }));
  check(sources.length === 1, 'nothing is reopened');
});

await scenario('a self-refreshing section that fails to refresh keeps itself and says so', ({ body }) => {
  const region = element('section');
  region.setAttribute('data-refresh', '');
  const note = element('p');
  note.setAttribute('data-refresh-status', '');
  region.appendChild(note);
  const detail = { shouldSwap: true, isError: true, elt: region, target: region,
    xhr: { status: 500, getResponseHeader: () => 'text/html' } };
  body.dispatchEvent({ type: 'htmx:beforeSwap', detail });
  check(detail.shouldSwap === false, 'the error does not replace the section');
  check(note.textContent.includes('could not be refreshed (500)'), 'the section says so: ' + note.textContent);
  check(note.textContent.includes('try again'), 'a refresh as the run moves on is retried later: ' + note.textContent);
  const ended = { shouldSwap: true, isError: true, elt: region, target: region,
    requestConfig: { triggeringEvent: { type: 'run-ended' } },
    xhr: { status: 503, getResponseHeader: () => 'text/html' } };
  body.dispatchEvent({ type: 'htmx:beforeSwap', detail: ended });
  check(ended.shouldSwap === false, 'the error does not replace the section');
  check(note.textContent.includes('reload the page') && !note.textContent.includes('try again'),
    '#195: a failed refresh for the run\'s end promises no retry: ' + note.textContent);
});

await scenario('a long run keeps at most 1000 events on the page', ({ sources, timeline }) => {
  for (let sequence = 3; sequence < 1103; sequence++) sources[0].emit('MODEL_STREAM_CHUNK', chunk(sequence));
  const shown = timeline.querySelectorAll('li.console-event');
  check(shown.length === 1000, 'at most 1000 events: ' + shown.length);
  check(Number(shown[0].getAttribute('data-sequence')) === 103, 'the oldest are removed first');
  check(String(timeline.children[0].textContent).includes('no longer shown'), 'the page says so');
});

await scenario('a non-HTML htmx response is never swapped into the page', ({ body }) => {
  const target = element('div');
  target.appendChild(element('span'));
  const detail = {
    shouldSwap: true, target,
    xhr: { getResponseHeader: () => 'application/problem+json', responseText: '{"code":"CSRF_TOKEN_INVALID"}' },
  };
  body.dispatchEvent({ type: 'htmx:beforeSwap', detail });
  check(detail.shouldSwap === false, 'the swap is cancelled');
  check(target.children.length === 1 && target.children[0].textContent.includes('CSRF_TOKEN_INVALID'),
    'a plain message replaces it: ' + (target.children[0] && target.children[0].textContent));
  const html = { shouldSwap: true, target, xhr: { getResponseHeader: () => 'text/html;charset=UTF-8' } };
  body.dispatchEvent({ type: 'htmx:beforeSwap', detail: html });
  check(html.shouldSwap === true, 'an HTML response is swapped as usual');
});

process.exitCode = failures ? 1 : 0;
console.log(failures ? `${failures} FAILED` : 'ALL PASSED');
