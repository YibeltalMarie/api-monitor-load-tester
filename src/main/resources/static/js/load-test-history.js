/**
 * load-test-history.js
 *
 * Handles the history page on load-test-history.html
 *
 * Two tabs:
 *
 * Tab 1 — Load Tests
 *   Fetches: GET /api/load-test/history
 *   Contract response fields per item:
 *     id, url, method, totalRequests, status,
 *     startTime, avgLatencyMs, successRatePercent
 *   status values: "COMPLETED" | "RUNNING" | "FAILED"
 *   Click row → /load-test/{id}/result  (COMPLETED or FAILED)
 *   Click row → /load-test/{id}/live    (RUNNING)
 *
 * Tab 2 — Monitored APIs
 *   Fetches: GET /api/monitor  (lazy — only fetched when tab is first opened)
 *   Contract response fields per item:
 *     id, url, method, intervalSeconds,
 *     currentStatus, lastCheckedAt, lastLatencyMs, uptimePercent24h
 *   currentStatus values: "UP" | "DOWN" | "UNKNOWN"
 *   lastLatencyMs: can be null
 *   Click row → /monitor/{id}
 *
 * Dev B guarantees: both endpoints return [] not null when empty
 *
 * Error handling (Decision 4): never silent
 */

// ── Tab state ────────────────────────────────────────────────────────
// Track whether monitor tab has been loaded already (lazy load)
let monitorLoaded = false;

// ── DOM — Tab buttons ────────────────────────────────────────────────
const tabLoadTests = document.getElementById('tab-load-tests');
const tabMonitored = document.getElementById('tab-monitored');

// ── DOM — Sections ───────────────────────────────────────────────────
const sectionLoadTests = document.getElementById('section-load-tests');
const sectionMonitored = document.getElementById('section-monitored');

// ── DOM — Load tests section ─────────────────────────────────────────
const runsLoadingEl   = document.getElementById('runs-loading');
const runsErrorEl     = document.getElementById('runs-error');
const runsErrorMsgEl  = document.getElementById('runs-error-msg');
const runsEmptyEl     = document.getElementById('runs-empty');
const runsTableEl     = document.getElementById('runs-table-wrapper');
const runsBodyEl      = document.getElementById('runs-body');

// ── DOM — Monitor section ────────────────────────────────────────────
const monitorLoadingEl  = document.getElementById('monitor-loading');
const monitorErrorEl    = document.getElementById('monitor-error');
const monitorErrorMsgEl = document.getElementById('monitor-error-msg');
const monitorEmptyEl    = document.getElementById('monitor-empty');
const monitorTableEl    = document.getElementById('monitor-table-wrapper');
const monitorBodyEl     = document.getElementById('monitor-body');

// ── Tab button styles ─────────────────────────────────────────────────
const activeTabClass = 'inline-flex items-center gap-2 px-4 py-2 rounded-lg ' +
    'bg-brand-500 text-white text-sm font-medium transition-all';
const inactiveTabClass = 'inline-flex items-center gap-2 px-4 py-2 rounded-lg ' +
    'border border-surface-border text-slate-400 text-sm font-medium ' +
    'hover:text-white hover:border-slate-500 transition-all';

// ── Entry point ───────────────────────────────────────────────────────
// Load tests tab is active by default on page load
loadRunsHistory();

// Tab click listeners
tabLoadTests.addEventListener('click', () => switchTab('load-tests'));
tabMonitored.addEventListener('click', () => switchTab('monitored'));

// ── Tab switching ─────────────────────────────────────────────────────

/**
 * Switches between the two tabs.
 * Monitored tab fetches data only on first open (lazy load).
 * @param {string} tab - 'load-tests' | 'monitored'
 */
function switchTab(tab) {
    if (tab === 'load-tests') {
        // Show load tests section
        tabLoadTests.className  = activeTabClass;
        tabMonitored.className  = inactiveTabClass;
        sectionLoadTests.classList.remove('hidden');
        sectionMonitored.classList.add('hidden');
    } else {
        // Show monitored section
        tabMonitored.className  = activeTabClass;
        tabLoadTests.className  = inactiveTabClass;
        sectionMonitored.classList.remove('hidden');
        sectionLoadTests.classList.add('hidden');

        // Lazy load — only fetch monitor data once
        if (!monitorLoaded) {
            loadMonitorHistory();
            monitorLoaded = true;
        }
    }
}

// ── Tab 1: Load test history ──────────────────────────────────────────

/**
 * Fetches GET /api/load-test/history and renders the runs table.
 * Contract: always returns [] not null when no runs exist.
 */
async function loadRunsHistory() {
    showRunsState('loading');

    try {
        const response = await fetch('/api/load-test/history');

        if (!response.ok) {
            const err = await response.json();
            showRunsState('error', err.message || 'Failed to load history.');
            return;
        }

        const runs = await response.json();

        if (runs.length === 0) {
            showRunsState('empty');
            return;
        }

        runsBodyEl.innerHTML = '';
        runs.forEach(run => {
            runsBodyEl.appendChild(buildRunRow(run));
        });

        showRunsState('table');

    } catch (err) {
        showRunsState('error', 'Could not connect to the server.');
        console.error('Runs history fetch error:', err);
    }
}

/**
 * Builds one <tr> for the load tests table.
 * Uses exact contract field names:
 *   id, url, method, totalRequests, status,
 *   startTime, avgLatencyMs, successRatePercent
 * @param {object} run
 * @returns {HTMLElement}
 */
function buildRunRow(run) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
        <td>
            <span class="mono text-slate-300 text-sm">
                ${escapeHtml(run.url)}
            </span>
            <span class="ml-2 badge-info">${escapeHtml(run.method)}</span>
        </td>
        <td class="text-slate-400">${run.totalRequests}</td>
        <td class="mono ${latencyColor(run.avgLatencyMs)}">
            ${run.avgLatencyMs != null ? run.avgLatencyMs + 'ms' : '—'}
        </td>
        <td class="mono ${successRateColor(run.successRatePercent)}">
            ${run.successRatePercent != null
                ? run.successRatePercent.toFixed(1) + '%'
                : '—'}
        </td>
        <td class="text-slate-500 text-xs">
            ${formatTimestamp(run.startTime)}
        </td>
        <td>${runStatusBadge(run.status)}</td>
        <td>${runActionBtn(run.id, run.status)}</td>
    `;
    return tr;
}

// ── Tab 2: Monitor history ────────────────────────────────────────────

/**
 * Fetches GET /api/monitor and renders the monitored endpoints table.
 * Contract: always returns [] not null when no endpoints exist.
 * Called only once — on first tab open (lazy load).
 */
async function loadMonitorHistory() {
    showMonitorState('loading');

    try {
        const response = await fetch('/api/monitor');

        if (!response.ok) {
            const err = await response.json();
            showMonitorState('error', err.message || 'Failed to load endpoints.');
            return;
        }

        const endpoints = await response.json();

        if (endpoints.length === 0) {
            showMonitorState('empty');
            return;
        }

        monitorBodyEl.innerHTML = '';
        endpoints.forEach(ep => {
            monitorBodyEl.appendChild(buildMonitorRow(ep));
        });

        showMonitorState('table');

    } catch (err) {
        showMonitorState('error', 'Could not connect to the server.');
        console.error('Monitor fetch error:', err);
    }
}

/**
 * Builds one <tr> for the monitored endpoints table.
 * Uses exact contract field names:
 *   id, url, method, intervalSeconds, currentStatus,
 *   lastCheckedAt, lastLatencyMs, uptimePercent24h
 * Click → /monitor/{id}
 * @param {object} ep
 * @returns {HTMLElement}
 */
function buildMonitorRow(ep) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
        <td>
            <span class="mono text-slate-300 text-sm">
                ${escapeHtml(ep.url)}
            </span>
        </td>
        <td>
            <span class="badge-info">${escapeHtml(ep.method)}</span>
        </td>
        <td class="text-slate-400">${ep.intervalSeconds}s</td>
        <td class="mono ${uptimeColor(ep.uptimePercent24h)}">
            ${ep.uptimePercent24h != null
                ? ep.uptimePercent24h.toFixed(1) + '%'
                : '—'}
        </td>
        <td class="mono text-brand-400">
            ${ep.lastLatencyMs != null ? ep.lastLatencyMs + 'ms' : '—'}
        </td>
        <td>${monitorStatusBadge(ep.currentStatus)}</td>
        <td>
            <a href="/monitor/${ep.id}"
               class="inline-flex items-center gap-1 px-2.5 py-1 rounded-md
                      text-xs text-slate-400 hover:text-white border
                      border-surface-border hover:border-slate-500
                      transition-all">
                View
                <svg class="h-3 w-3" fill="none" viewBox="0 0 24 24"
                     stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round"
                          d="M9 5l7 7-7 7"/>
                </svg>
            </a>
        </td>
    `;
    return tr;
}

// ── Render helpers ────────────────────────────────────────────────────

function runStatusBadge(status) {
    if (status === 'COMPLETED')
        return '<span class="badge-success">Completed</span>';
    if (status === 'FAILED')
        return '<span class="badge-danger">Failed</span>';
    if (status === 'RUNNING')
        return `<span class="inline-flex items-center gap-1.5 badge-warning">
                    <svg class="h-3 w-3 spinner" fill="none" viewBox="0 0 24 24"
                         stroke="currentColor" stroke-width="2.5">
                        <path stroke-linecap="round" stroke-linejoin="round"
                              d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182m0-4.991v4.99"/>
                    </svg>
                    Running
                </span>`;
    return `<span class="badge-info">${escapeHtml(status)}</span>`;
}

function runActionBtn(id, status) {
    if (status === 'COMPLETED' || status === 'FAILED') {
        return `<a href="/load-test/${id}/result"
                   class="inline-flex items-center gap-1 px-2.5 py-1
                          rounded-md text-xs text-slate-400 hover:text-white
                          border border-surface-border hover:border-slate-500
                          transition-all">
                    View
                    <svg class="h-3 w-3" fill="none" viewBox="0 0 24 24"
                         stroke="currentColor" stroke-width="2">
                        <path stroke-linecap="round" stroke-linejoin="round"
                              d="M9 5l7 7-7 7"/>
                    </svg>
                </a>`;
    }
    if (status === 'RUNNING') {
        return `<a href="/load-test/${id}/live"
                   class="inline-flex items-center gap-1 px-2.5 py-1
                          rounded-md text-xs text-yellow-400
                          border border-yellow-400/20
                          hover:bg-yellow-400/10 transition-all">
                    Live
                    <svg class="h-3 w-3" fill="none" viewBox="0 0 24 24"
                         stroke="currentColor" stroke-width="2">
                        <path stroke-linecap="round" stroke-linejoin="round"
                              d="M9 5l7 7-7 7"/>
                    </svg>
                </a>`;
    }
    return '';
}

function monitorStatusBadge(status) {
    if (status === 'UP')      return '<span class="badge-success">UP</span>';
    if (status === 'DOWN')    return '<span class="badge-danger">DOWN</span>';
    return '<span class="badge-info">UNKNOWN</span>';
}

function latencyColor(ms) {
    if (ms == null)   return 'text-slate-500';
    return ms > 500   ? 'text-yellow-400' : 'text-brand-400';
}

function successRateColor(rate) {
    if (rate == null) return 'text-slate-500';
    return rate < 90  ? 'text-red-400' : 'text-green-400';
}

function uptimeColor(pct) {
    if (pct == null) return 'text-slate-500';
    if (pct >= 99)   return 'text-green-400';
    if (pct >= 95)   return 'text-yellow-400';
    return 'text-red-400';
}

function formatTimestamp(isoString) {
    if (!isoString) return '—';
    return isoString.substring(0, 16).replace('T', ' ');
}

function escapeHtml(str) {
    if (!str) return '';
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

// ── State management ──────────────────────────────────────────────────

function showRunsState(state, message = '') {
    runsLoadingEl.classList.add('hidden');
    runsErrorEl.classList.add('hidden');
    runsEmptyEl.classList.add('hidden');
    runsTableEl.classList.add('hidden');

    if (state === 'loading') runsLoadingEl.classList.remove('hidden');
    if (state === 'error')   { runsErrorMsgEl.textContent = message; runsErrorEl.classList.remove('hidden'); }
    if (state === 'empty')   runsEmptyEl.classList.remove('hidden');
    if (state === 'table')   runsTableEl.classList.remove('hidden');
}

function showMonitorState(state, message = '') {
    monitorLoadingEl.classList.add('hidden');
    monitorErrorEl.classList.add('hidden');
    monitorEmptyEl.classList.add('hidden');
    monitorTableEl.classList.add('hidden');

    if (state === 'loading') monitorLoadingEl.classList.remove('hidden');
    if (state === 'error')   { monitorErrorMsgEl.textContent = message; monitorErrorEl.classList.remove('hidden'); }
    if (state === 'empty')   monitorEmptyEl.classList.remove('hidden');
    if (state === 'table')   monitorTableEl.classList.remove('hidden');
}