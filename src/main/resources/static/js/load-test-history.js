/**
 * load-test-history.js
 *
 * Handles the test history page on load-test-history.html
 *
 * Responsibilities:
 *   1. Fetch GET /api/load-test/history on page load
 *   2. If fetch fails  → show error state
 *   3. If list empty   → show empty state
 *   4. If list has items → build table rows and show table
 *
 * Contract: GET /api/load-test/history
 * Response: Array of TestRunSummary objects
 *   [
 *     {
 *       "id":                 "a1b2c3d4",
 *       "url":                "https://api.example.com/users",
 *       "method":             "GET",
 *       "totalRequests":      100,
 *       "status":             "COMPLETED",
 *       "startTime":          "2026-05-17T10:30:00",
 *       "avgLatencyMs":       142,
 *       "successRatePercent": 97.0
 *     }
 *   ]
 *
 * Dev B guarantees: always returns [] not null when no runs exist
 * status values: "COMPLETED" | "RUNNING" | "FAILED"
 */

// ── DOM element references ──────────────────────────────────────────
const loadingEl      = document.getElementById('history-loading');
const errorEl        = document.getElementById('history-error');
const errorMsgEl     = document.getElementById('history-error-msg');
const emptyEl        = document.getElementById('history-empty');
const tableWrapperEl = document.getElementById('history-table-wrapper');
const runsBodyEl     = document.getElementById('runs-body');

// ── Entry point ─────────────────────────────────────────────────────
loadHistory();

/**
 * Fetches the run history from the API and renders the result.
 */
async function loadHistory() {
    try {
        const response = await fetch('/api/load-test/history');

        if (!response.ok) {
            // Contract error shape: { status, error, message, timestamp }
            const err = await response.json();
            showError(err.message || 'Failed to load history.');
            return;
        }

        // Contract: always an array, never null
        const runs = await response.json();

        if (runs.length === 0) {
            showEmpty();
            return;
        }

        buildTable(runs);
        showTable();

    } catch (err) {
        // Network error
        showError('Could not connect to the server. Please check it is running.');
        console.error('History fetch error:', err);
    }
}

/**
 * Builds all table rows from the runs array and injects into tbody.
 * Uses exact field names from the contract.
 * @param {Array} runs
 */
function buildTable(runs) {
    runsBodyEl.innerHTML = '';

    runs.forEach(run => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td>
                <span class="mono text-slate-300 text-sm">${escapeHtml(run.url)}</span>
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
            <td>${statusBadge(run.status)}</td>
            <td>${actionLink(run.id, run.status)}</td>
        `;
        runsBodyEl.appendChild(tr);
    });
}

// ── Render helpers ──────────────────────────────────────────────────

/**
 * Returns the correct status badge HTML based on status string.
 * Contract status values: "COMPLETED" | "RUNNING" | "FAILED"
 * @param {string} status
 * @returns {string} HTML string
 */
function statusBadge(status) {
    if (status === 'COMPLETED') {
        return '<span class="badge-success">Completed</span>';
    }
    if (status === 'FAILED') {
        return '<span class="badge-danger">Failed</span>';
    }
    if (status === 'RUNNING') {
        return `
            <span class="inline-flex items-center gap-1.5 badge-warning">
                <svg class="h-3 w-3 spinner" fill="none" viewBox="0 0 24 24"
                     stroke="currentColor" stroke-width="2.5">
                    <path stroke-linecap="round" stroke-linejoin="round"
                          d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992
                             m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7
                             M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182m0-4.991v4.99"/>
                </svg>
                Running
            </span>`;
    }
    return `<span class="badge-info">${escapeHtml(status)}</span>`;
}

/**
 * Returns the correct action link based on status.
 * COMPLETED or FAILED → link to result page
 * RUNNING             → link to live page
 * @param {string} id
 * @param {string} status
 * @returns {string} HTML string
 */
function actionLink(id, status) {
    if (status === 'COMPLETED' || status === 'FAILED') {
        return `
            <a href="/load-test/${id}/result"
               class="text-xs text-slate-500 hover:text-brand-400 transition-colors">
                View →
            </a>`;
    }
    if (status === 'RUNNING') {
        return `
            <a href="/load-test/${id}/live"
               class="text-xs text-yellow-400 hover:text-yellow-300 transition-colors">
                Live →
            </a>`;
    }
    return '';
}

/**
 * Returns Tailwind color class based on avgLatencyMs value.
 * null or fast → brand-400 (blue)
 * slow (>500ms) → yellow-400
 * @param {number|null} ms
 * @returns {string}
 */
function latencyColor(ms) {
    if (ms == null) return 'text-slate-500';
    return ms > 500 ? 'text-yellow-400' : 'text-brand-400';
}

/**
 * Returns Tailwind color class based on successRatePercent value.
 * null → slate
 * below 90% → red
 * 90% or above → green
 * @param {number|null} rate
 * @returns {string}
 */
function successRateColor(rate) {
    if (rate == null) return 'text-slate-500';
    return rate < 90 ? 'text-red-400' : 'text-green-400';
}

/**
 * Formats ISO 8601 timestamp to readable string.
 * Contract: startTime is "2026-05-17T10:30:00"
 * Output: "2026-05-17 10:30"
 * @param {string} isoString
 * @returns {string}
 */
function formatTimestamp(isoString) {
    if (!isoString) return '—';
    return isoString.substring(0, 16).replace('T', ' ');
}

/**
 * Escapes HTML special characters to prevent XSS.
 * Always escape user-provided strings before injecting into innerHTML.
 * @param {string} str
 * @returns {string}
 */
function escapeHtml(str) {
    if (!str) return '';
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

// ── State management ────────────────────────────────────────────────

function showError(message) {
    loadingEl.classList.add('hidden');
    errorMsgEl.textContent = message;
    errorEl.classList.remove('hidden');
}

function showEmpty() {
    loadingEl.classList.add('hidden');
    emptyEl.classList.remove('hidden');
}

function showTable() {
    loadingEl.classList.add('hidden');
    tableWrapperEl.classList.remove('hidden');
}