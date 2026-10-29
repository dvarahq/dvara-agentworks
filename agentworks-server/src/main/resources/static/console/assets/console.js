/*
 * Dvara AgentWorks Web Console: the live run timeline and page-wide htmx guards (issue #36).
 *
 * The timeline follows GET /api/v1/runs/{id}/stream (#35) with a browser EventSource, after the last event the page
 * rendered. Every EventSource gets a random client id held only in memory (never in storage, so a duplicated tab never
 * shares it). The server's opening frame gives the browser a last event id, so the browser's own reconnect always
 * sends Last-Event-ID and supersedes this client's dead stream (#186, #190). Control events are handled as the API
 * documents:
 *   stream-end                       -> stop; the run has ended.
 *   reconnect                        -> nothing to do: the browser reconnects after the retry hint.
 *   stream-error, stop codes         -> stop and say why.
 *   stream-error, reconnect codes    -> nothing to do: the browser reconnects.
 *   stream-error, resync codes       -> re-read the run and follow again from its current sequence.
 * A connection the server refuses for good (204, or 404, 409, or 410 on a reconnect) closes the EventSource; the run
 * is then re-read, and followed again from its current sequence unless it has ended.
 * Every value is written with textContent: nothing from an event or a response is ever parsed as HTML.
 */
(function () {
    'use strict';

    var STOP = ['AUTHORIZATION_REVOKED', 'CORRUPT_HISTORY', 'STREAM_SUPERSEDED'];
    var RESYNC = ['EVENT_HISTORY_EXPIRED', 'EVENT_SEQUENCE_AHEAD', 'NOT_FOUND'];
    /** The most events the page keeps; older ones are removed, and the page says so. */
    var MAX_EVENTS = 1000;
    /**
     * How many times in a row the timeline follows again before it gives up: failed cycles in a row (connections that
     * ended refused without delivering an event, unless they stayed open for HEALTHY_MS first), or resynchronizations
     * in a row that received no event.
     */
    var MAX_RESYNCS = 3;
    /**
     * A connection that stayed open this long was healthy even without an event (an idle, blocked, or paused run): a
     * refusal after it starts a new count, so occasional refusals over a long, idle page never add up (#195). It is
     * twice the server's default heartbeat interval.
     */
    var HEALTHY_MS = 30000;

    function clientId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
        var bytes = new Uint8Array(16);
        window.crypto.getRandomValues(bytes);
        return Array.prototype.map.call(bytes, function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
    }

    function text(tag, className, value) {
        var element = document.createElement(tag);
        if (className) element.className = className;
        element.textContent = value == null ? '' : String(value);
        return element;
    }

    function Timeline(list) {
        this.list = list;
        this.status = document.getElementById('stream-status');
        this.streamUrl = list.getAttribute('data-stream-url');
        this.runUrl = list.getAttribute('data-run-url');
        this.types = (list.getAttribute('data-event-types') || '').split(',').filter(Boolean);
        this.terminalLifecycles = (list.getAttribute('data-terminal-lifecycles') || '').split(',').filter(Boolean);
        /** Where the stream follows from. */
        this.position = Number(list.getAttribute('data-cursor') || '0');
        /** The last event on the page; an event at or before it is never shown twice. */
        this.rendered = this.position;
        /** The page was rendered for a run that had already ended: it never announces the end again. */
        this.renderedTerminal = list.getAttribute('data-terminal') === 'true';
        this.terminal = this.renderedTerminal;
        this.reason = null;
        this.trimmed = false;
        this.resyncs = 0;
        /** Failed cycles in a row: connections refused without an event since a healthy period (#195). */
        this.refusals = 0;
        /** When the current connection opened, or null while no connection is open. */
        this.openedAt = null;
        /** How long the last connection actually stayed open, measured when it ended (#200). */
        this.lastConnection = 0;
        /** The page was hidden while following, for example into the back/forward cache (#195). */
        this.suspended = false;
        /** A re-read of the run is in flight; the page follows again when it answers. */
        this.resyncing = false;
        /** Bumped when the page is hidden, so that a re-read begun before then never opens a second stream. */
        this.generation = 0;
        this.source = null;
    }

    Timeline.prototype.say = function (message) {
        if (this.status) this.status.textContent = message;
    };

    Timeline.prototype.isTerminal = function (lifecycle) {
        return this.terminalLifecycles.indexOf(lifecycle) >= 0;
    };

    Timeline.prototype.changed = function () {
        document.body.dispatchEvent(new CustomEvent('run-changed'));
    };

    /** The run has ended: the summary and the actions refresh once more, unless the page already showed the end. */
    Timeline.prototype.ended = function (lifecycle) {
        this.terminal = true;
        this.say('The run has ended' + (lifecycle ? ': ' + lifecycle : '') + '.');
        if (!this.renderedTerminal) document.body.dispatchEvent(new CustomEvent('run-ended'));
    };

    Timeline.prototype.open = function () {
        var self = this;
        var url = this.streamUrl + '?cursor=' + encodeURIComponent(String(this.position)) + '&client='
            + encodeURIComponent(clientId());
        var source = new EventSource(url);
        this.source = source;
        if (!this.terminal) this.say('Connecting to the live stream…');
        this.types.forEach(function (type) {
            source.addEventListener(type, function (message) { self.event(message); });
        });
        source.addEventListener('stream-end', function (message) {
            self.close();
            var end = self.parse(message);
            self.ended(end && end.lifecycle);
        });
        source.addEventListener('reconnect', function (message) {
            var reconnect = self.parse(message);
            self.reason = reconnect && reconnect.reason ? reconnect.reason : null;
            self.say('Reconnecting' + (self.reason ? ' (' + self.reason + ')' : '') + '…');
        });
        source.addEventListener('stream-error', function (message) {
            var error = self.parse(message) || {};
            if (STOP.indexOf(error.code) >= 0) {
                self.close();
                self.say(error.code === 'STREAM_SUPERSEDED'
                    ? 'This page stopped following the run because the same stream was resumed elsewhere. Reload to follow it here.'
                    : error.code === 'AUTHORIZATION_REVOKED'
                        ? 'Your session no longer allows reading this run. Sign in again.'
                        : 'The run history could not be verified (' + error.code + ').');
            } else if (RESYNC.indexOf(error.code) >= 0) {
                self.close();
                self.resync(error.code, true, 'resyncs');
            } else {
                self.reason = error.code || 'unknown';
                self.say('The stream is unavailable (' + self.reason + '); reconnecting…');
            }
        });
        source.addEventListener('open', function () {
            // An open that is refused before it delivers an event is still a failed cycle; only an event, or a
            // connection that stayed open for HEALTHY_MS, ends the run of failures (#195).
            self.openedAt = Date.now();
            self.reason = null;
            if (!self.terminal) self.say('Following the run live.');
        });
        source.addEventListener('error', function () {
            // A connection that was open has ended here; the time spent reconnecting afterwards is not open time.
            self.connectionEnded();
            if (source.readyState === EventSource.CLOSED) {
                // The server answered the (re)connect with something other than a stream: 204 for an ended run, or
                // a refusal such as 404, 409, or 410. The run itself says which.
                self.source = null;
                if (self.lastConnection >= HEALTHY_MS) self.refusals = 0;
                self.lastConnection = 0;
                self.resync('the stream was refused', self.refusals > 0, 'refusals');
            } else if (!self.reason) {
                self.say('Connection lost; reconnecting…');
            }
        });
    };

    /** Records how long the connection that just ended was actually open. */
    Timeline.prototype.connectionEnded = function () {
        if (this.openedAt === null) return;
        this.lastConnection = Date.now() - this.openedAt;
        this.openedAt = null;
    };

    Timeline.prototype.close = function () {
        this.connectionEnded();
        if (this.source) this.source.close();
        this.source = null;
    };

    Timeline.prototype.parse = function (message) {
        try {
            return JSON.parse(message.data);
        } catch (unreadable) {
            return null;
        }
    };

    Timeline.prototype.event = function (message) {
        var envelope = this.parse(message);
        if (!envelope || typeof envelope.sequence !== 'number') return;
        this.resyncs = 0;
        this.refusals = 0;
        this.position = Math.max(this.position, envelope.sequence);
        if (envelope.sequence <= this.rendered) return;
        this.rendered = envelope.sequence;
        var item = document.createElement('li');
        item.className = 'console-event';
        item.setAttribute('data-sequence', String(envelope.sequence));
        var header = document.createElement('div');
        header.className = 'd-flex flex-wrap gap-2 align-items-baseline';
        header.appendChild(text('span', 'badge text-bg-light border', envelope.sequence));
        header.appendChild(text('strong', '', envelope.type));
        header.appendChild(text('span', 'small text-body-secondary', envelope.category));
        header.appendChild(text('time', 'small text-body-secondary', envelope.createdAt));
        if (envelope.actor) header.appendChild(text('span', 'small text-body-secondary', envelope.actor));
        item.appendChild(header);
        if (envelope.payload !== undefined && envelope.payload !== null) {
            var details = document.createElement('details');
            details.appendChild(text('summary', 'small', 'Payload'));
            details.appendChild(text('pre', 'small mb-0', JSON.stringify(envelope.payload, null, 2)));
            item.appendChild(details);
        }
        this.list.appendChild(item);
        this.trim();
        this.changed();
    };

    /** Keeps at most MAX_EVENTS events on the page, so that a long run cannot exhaust the browser's memory. */
    Timeline.prototype.trim = function () {
        var events = this.list.querySelectorAll('li.console-event');
        if (events.length <= MAX_EVENTS) return;
        for (var index = 0; index < events.length - MAX_EVENTS; index++) this.list.removeChild(events[index]);
        if (!this.trimmed) {
            this.trimmed = true;
            this.list.insertBefore(text('li', 'console-gap small text-body-secondary',
                'Earlier events are no longer shown; reload the page to see the run from its start.'),
                this.list.firstChild);
        }
    };

    /**
     * Leaving the page closes its stream; the page remembers that it was following, also while a re-read of the run was
     * in flight, whose answer is then ignored (#195).
     */
    Timeline.prototype.suspend = function () {
        if (!this.source && !this.resyncing) return;
        this.close();
        this.resyncing = false;
        this.generation++;
        this.suspended = true;
    };

    /**
     * A page restored from the back/forward cache follows the run again from the last event it shows, if it was
     * following when it was hidden; a run that ended meanwhile is then re-read and announced (#195).
     */
    Timeline.prototype.resume = function () {
        if (!this.suspended) return;
        this.suspended = false;
        this.refusals = 0;
        this.open();
    };

    /**
     * Re-reads the run and follows it again. Without {@code jump}, it follows on from the last event on the page, so
     * nothing is skipped (a transient refusal); with it (a resync code, or a refusal that repeats), it follows from the
     * run's current sequence and marks the events the page does not show. An ended run whose every event is on the page
     * is announced instead. {@code counter} names the count of attempts in a row ('refusals' or 'resyncs'); it gives
     * up after MAX_RESYNCS of them.
     */
    Timeline.prototype.resync = function (why, jump, counter) {
        var self = this;
        var generation = this.generation;
        this.resyncing = true;
        fetch(this.runUrl, {credentials: 'same-origin', headers: {'Accept': 'application/json'}})
            .then(function (response) {
                if (generation !== self.generation) return null;
                if (response.status === 401) {
                    self.say('Your session has ended. Sign in again to follow the run.');
                    return null;
                }
                if (!response.ok) {
                    self.say('The run can no longer be read (' + response.status + '). Reload the page.');
                    return null;
                }
                return response.json();
            })
            .then(function (run) {
                // A page hidden meanwhile follows again when it is shown (resume), whenever this answer arrives.
                if (generation !== self.generation) return;
                self.resyncing = false;
                if (!run) return;
                self.changed();
                if (self.isTerminal(run.lifecycle) && run.eventSequence <= self.rendered) {
                    self.ended(run.lifecycle);
                    return;
                }
                self[counter]++;
                if (self[counter] > MAX_RESYNCS) {
                    self.say('The live stream is unavailable. Reload the page to try again.');
                    return;
                }
                if (jump) {
                    if (run.eventSequence > self.rendered) {
                        self.list.appendChild(text('li', 'console-gap small text-body-secondary',
                            'Events ' + (self.rendered + 1) + '–' + run.eventSequence + ' are not shown (' + why
                            + '); reload the page to see them.'));
                        self.rendered = run.eventSequence;
                    }
                    self.position = run.eventSequence;
                } else {
                    self.position = Math.min(self.rendered, run.eventSequence);
                }
                self.open();
            })
            .catch(function () {
                if (generation !== self.generation) return;
                self.resyncing = false;
                self.say('The live stream is unavailable. Reload the page to try again.');
            });
    };

    /**
     * htmx responses are checked before they are swapped. A self-refreshing region (data-refresh) that fails to
     * refresh keeps its content and its refreshing, and says so inside it. A response that is not HTML (for example the
     * JSON problem of a refused CSRF token after the session changed in another tab) is never swapped into the page; a
     * plain message says what happened.
     */
    function guardSwaps() {
        document.body.addEventListener('htmx:beforeSwap', function (event) {
            var detail = event.detail;
            if (!detail.shouldSwap) return;
            var region = detail.elt && detail.elt.hasAttribute && detail.elt.hasAttribute('data-refresh')
                ? detail.elt : null;
            if (region && detail.isError) {
                detail.shouldSwap = false;
                var status = region.querySelector('[data-refresh-status]');
                // A refresh for the run's end has no later refresh to retry it (#195).
                var ended = detail.requestConfig && detail.requestConfig.triggeringEvent
                    && detail.requestConfig.triggeringEvent.type === 'run-ended';
                if (status) status.textContent = 'This section could not be refreshed ('
                    + (detail.xhr ? detail.xhr.status : 'error') + ')'
                    + (ended ? '; reload the page to see the run\'s final state.'
                        : '; it will try again when the run moves on.');
                return;
            }
            var type = detail.xhr && detail.xhr.getResponseHeader('Content-Type') || '';
            if (type.indexOf('text/html') === 0 || !detail.target) return;
            detail.shouldSwap = false;
            var code = '';
            try {
                code = JSON.parse(detail.xhr.responseText).code || '';
            } catch (unreadable) {
                code = '';
            }
            var message = text('div', 'alert alert-danger mt-2', 'The request was refused'
                + (code ? ' (' + code + ')' : '') + '. Your session may have changed in another tab; reload the page.');
            message.setAttribute('role', 'alert');
            while (detail.target.firstChild) detail.target.removeChild(detail.target.firstChild);
            detail.target.appendChild(message);
        });
    }

    function start() {
        guardSwaps();
        var list = document.getElementById('timeline');
        if (!list || !list.hasAttribute('data-run-stream')) return;
        var timeline = new Timeline(list);
        if (list.getAttribute('data-complete') === 'true') {
            // An ended run whose every event is on the page: nothing is left to follow.
            timeline.say('The run has ended.');
            return;
        }
        if (!window.EventSource) {
            timeline.say('This browser cannot follow the run live; reload the page for new events.');
            return;
        }
        // Leaving or reloading the page closes its connection at once, so the server frees the stream's slot at its
        // next write instead of holding it for a half-open connection. A page restored from the back/forward cache
        // (pageshow with persisted) follows again, rather than claiming to be live while frozen (#195).
        window.addEventListener('pagehide', function () { timeline.suspend(); });
        window.addEventListener('pageshow', function (event) { if (event && event.persisted) timeline.resume(); });
        timeline.open();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
})();
