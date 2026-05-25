/**
 * load-test-result.js
 *
 * Handles the result page on load-test-result.html
 *
 * Responsibilities:
 *   1. Read testRunId from the current URL
 *   2. Fetch GET /api/load-test/{id} for full result + summary stats
 *   3. Populate all stat cards, header info, percentile bars, error table
 *   4. Set export CSV link href
 *   5. On fetch failure → show error state
 *
 * NOTE: Chart.js bar chart is Group 2 work.
 * GET /api/load-test/{id}/results is also Group 2.
 * This file only handles Group 1 — stats display.
 *
 * Contract: GET /api/load-test/{id}
 * Response: TestRunResult object
 *   {
 *     "id":              "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
 *     "url":             "https://api.example.com/users",
 *     "method":          "GET",
 *     "totalRequests":   100,
 *     "concurrency":     10,
 *     "status":          "COMPLETED",
 *     "startTime":       "2026-05-17T10:30:00",
 *     "endTime":         "2026-05-17T10:30:12",
 *     "durationSeconds": 12,
 *     "summary": {
 *       "totalSent":          100,
 *       "successCount":       97,
 *       "failureCount":       3,
 *       "successRatePercent": 97.0,
 *       "avgLatencyMs":       142,
 *       "minLatencyMs":       89,
 *       "maxLatencyMs":       854,
 *       "p50LatencyMs":       128,
 *       "p95LatencyMs":       430,
 *       "p99LatencyMs":       790,
 *       "requestsPerSecond":  8.3,
 *       "errors": [
 *         { "statusCode": 503, "count": 2 },
 *         { "statusCode": 0,   "count": 1, "reason": "timeout" }
 *       ]
 *     }
 *   }
 */

// ── Read testRunId from URL ──────────────────────────────────────────
// URL pattern: /load-test/{id}/result
// pathname.split('/') → ['', 'load-test', 'a1b2c3d4', 'result']
const testRunId = window.location.pathname.split('/')[2];

// ── DOM element references ──────────────────────────────────────────
const loadingEl       = document.getElementById('result-loading');
const errorEl         = document.getElementById('result-error');
const errorMsgEl      = document.getElementById('result-error-msg');
const contentEl       = document.getElementById('result-content');

// Header elements
const methodEl        = document.getElementById('result-method');
const urlEl           = document.getElementById('result-url');
const metaEl          = document.getElementById('result-meta');
const exportLinkEl    = document.getElementById('export-link');

// Stat card elements
const statTotalSent   = document.getElementById('stat-total-sent');
const statSuccess     = document.getElementById('stat-success-count');
const statFailures    = document.getElementById('stat-failure-count');
const statAvgLatency  = document.getElementById('stat-avg-latency');
const statReqPerSec   = document.getElementById('stat-req-per-sec');
const statSuccessRate = document.getElementById('stat-success-rate');

// Percentile bar elements
const barMin  = document.getElementById('bar-min');
const barP50  = document.getElementById('bar-p50');
const barP95  = document.getElementById('bar-p95');
const barP99  = document.getElementById('bar-p99');

// Percentile value elements
const valMin  = document.getElementById('val-min');
const valP50  = document.getElementById('val-p50');
const valP95  = document.getElementById('val-p95');
const valP99  = document.getElementById('val-p99');
const valMax  = document.getElementById('val-max');

// Error table elements
const errorsSectionEl = document.getElementById('errors-section');
const errorsBodyEl    = document.getElementById('errors-body');

// ── Entry point ─────────────────────────────────────────────────────
loadResult();

/**
 * Fetches the full test result from the API and renders all sections.
 */
async function loadResult() {
    try {
        const response = await fetch(`/api/load-test/${testRunId}`);

        if (!response.ok) {
            const err = await response.json();
            showError(err.message || 'Failed to load test results.');
            return;
        }

        const data = await response.json();

        try {
            populateHeader(data);
            populateStatCards(data.summary);
            populatePercentiles(data.summary);
            populateErrors(data.summary.errors);
            showContent();
        } catch (renderErr) {
            console.error('Render error:', renderErr);
            showError('Data loaded but could not render results: ' + renderErr.message);
        }

    } catch (err) {
        showError('Could not connect to the server. Please check it is running.');
        console.error('Result fetch error:', err);
    }
}

// ── Populate functions ──────────────────────────────────────────────

/**
 * Fills in the page header: method badge, URL, timestamp, export link.
 * Uses: data.method, data.url, data.startTime, data.durationSeconds, data.id
 * @param {object} data - full TestRunResult from contract
 */
function populateHeader(data) {
    // method badge text
    methodEl.textContent = data.method;

    // target URL
    urlEl.textContent = data.url;

    // "2026-05-17 10:30 · 12s duration"
    const formattedTime = data.startTime
        ? data.startTime.substring(0, 16).replace('T', ' ')
        : '—';
    metaEl.textContent = `${formattedTime} · ${data.durationSeconds}s duration`;

    // CSV export link — plain href, browser handles download
    // Contract: GET /api/load-test/{id}/export
    exportLinkEl.href = `/api/load-test/${data.id}/export`;
}

/**
 * Fills all 6 stat cards.
 * Uses exact field names from contract summary object:
 *   totalSent, successCount, failureCount,
 *   avgLatencyMs, requestsPerSecond, successRatePercent
 * @param {object} summary
 */
function populateStatCards(summary) {
    statTotalSent.textContent   = summary.totalSent;
    statSuccess.textContent     = summary.successCount;
    statFailures.textContent    = summary.failureCount;
    statAvgLatency.textContent  = summary.avgLatencyMs + 'ms';
    statReqPerSec.textContent   = summary.requestsPerSecond.toFixed(1);
    statSuccessRate.textContent = summary.successRatePercent.toFixed(1) + '%';
}

/**
 * Fills the percentile progress bars and value labels.
 * Bar widths are calculated as a percentage of maxLatencyMs.
 * Uses: minLatencyMs, p50LatencyMs, p95LatencyMs, p99LatencyMs, maxLatencyMs
 * @param {object} summary
 */
function populatePercentiles(summary) {
    const max = summary.maxLatencyMs;

    // Set bar widths relative to max
    barMin.style.width = pct(summary.minLatencyMs, max);
    barP50.style.width = pct(summary.p50LatencyMs, max);
    barP95.style.width = pct(summary.p95LatencyMs, max);
    barP99.style.width = pct(summary.p99LatencyMs, max);
    // max bar is always 100% — set in HTML already

    // Set value labels
    valMin.textContent = summary.minLatencyMs + 'ms';
    valP50.textContent = summary.p50LatencyMs + 'ms';
    valP95.textContent = summary.p95LatencyMs + 'ms';
    valP99.textContent = summary.p99LatencyMs + 'ms';
    valMax.textContent = summary.maxLatencyMs + 'ms';
}

/**
 * Builds the error breakdown table rows.
 * Contract error shape: { statusCode, count, reason }
 * reason can be null — show "HTTP error" as fallback
 * Only shows the section if errors array is not empty.
 * @param {Array} errors
 */
function populateErrors(errors) {
    if (!errors || errors.length === 0) return;

    errorsBodyEl.innerHTML = '';

    errors.forEach(err => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td class="mono text-red-400">${err.statusCode}</td>
            <td class="text-slate-400">${err.count}</td>
            <td class="text-slate-500">${err.reason != null ? escapeHtml(err.reason) : 'HTTP error'}</td>
        `;
        errorsBodyEl.appendChild(tr);
    });

    // Show the errors section
    errorsSectionEl.classList.remove('hidden');
}

// ── Utility functions ───────────────────────────────────────────────

/**
 * Calculates percentage width string for progress bars.
 * @param {number} value
 * @param {number} max
 * @returns {string} e.g. "42.5%"
 */
function pct(value, max) {
    if (!max || max === 0) return '0%';
    return ((value / max) * 100).toFixed(1) + '%';
}

/**
 * Escapes HTML special characters to prevent XSS.
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

function showContent() {
    loadingEl.classList.add('hidden');
    contentEl.classList.remove('hidden');
}