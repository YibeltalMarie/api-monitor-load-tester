/**
 * live-results.js
 *
 * Handles the live results page on load-test-live.html
 *
 * Responsibilities:
 *   1. Read testRunId from the current URL
 *   2. Open EventSource on /api/load-test/{id}/stream
 *   3. On each onmessage event → update counters + push to Chart.js
 *   4. On done event          → close stream, show final summary, enable button
 *   5. On onerror             → close stream, show error message
 *
 * Contract: GET /api/load-test/{id}/stream
 *
 * Each event during test (onmessage):
 *   { index, latencyMs, statusCode, success, timestamp }
 *
 * Final event when done (named event "done"):
 *   { totalSent, successCount, failureCount, avgLatencyMs }
 *
 * Decisions doc rule:
 *   source.close() MUST be called after the done event.
 *   Dev B closes server side. Dev C closes client side.
 *   Failing to do this leaves a hanging connection open forever.
 *
 * Error handling (Decision 4):
 *   Never silent. Always show a message to the user.
 *   onerror → show inline error, close stream.
 */

// ── Read testRunId from URL ──────────────────────────────────────────
// URL pattern: /load-test/{id}/live
// pathname.split('/') → ['', 'load-test', 'a1b2c3d4', 'live']
const testRunId = window.location.pathname.split('/')[2];

// ── DOM element references ──────────────────────────────────────────
const liveErrorEl      = document.getElementById('live-error');
const liveErrorMsgEl   = document.getElementById('live-error-msg');
const statusBadgeEl    = document.getElementById('live-status-badge');
const liveSpinnerEl    = document.getElementById('live-spinner');

// Counters
const counterSentEl     = document.getElementById('counter-sent');
const counterSuccessEl  = document.getElementById('counter-success');
const counterFailuresEl = document.getElementById('counter-failures');

// Final summary
const finalSummaryEl       = document.getElementById('final-summary');
const summaryTotalSentEl   = document.getElementById('summary-total-sent');
const summarySuccessEl     = document.getElementById('summary-success');
const summaryFailuresEl    = document.getElementById('summary-failures');
const summaryAvgLatencyEl  = document.getElementById('summary-avg-latency');
const viewResultsBtnEl     = document.getElementById('view-results-btn');

// ── State ───────────────────────────────────────────────────────────
let sentCount    = 0;
let successCount = 0;
let failureCount = 0;

// ── Chart.js line chart setup ───────────────────────────────────────
// Initialised once, updated on every SSE event
const liveCtx = document.getElementById('live-chart').getContext('2d');
const liveChart = new Chart(liveCtx, {
    type: 'line',
    data: {
        // x axis: request index numbers (1, 2, 3...)
        labels: [],
        datasets: [{
            label: 'Latency (ms)',
            data: [],
            borderColor: '#0ea5e9',
            backgroundColor: 'rgba(14,165,233,0.05)',
            borderWidth: 2,
            pointRadius: 2,
            pointHoverRadius: 4,
            pointBackgroundColor: '#0ea5e9',
            tension: 0.3,
            fill: true,
        }]
    },
    options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false, // disable animation for real-time performance
        plugins: {
            legend: { display: false },
            tooltip: {
                backgroundColor: '#1e293b',
                borderColor: '#334155',
                borderWidth: 1,
                titleColor: '#e2e8f0',
                bodyColor: '#94a3b8',
                callbacks: {
                    label: ctx => `  ${ctx.parsed.y}ms`
                }
            }
        },
        scales: {
            x: {
                grid:  { color: 'rgba(51,65,85,0.4)' },
                ticks: {
                    color: '#64748b',
                    font: { family: 'DM Sans' },
                    maxTicksLimit: 10
                },
                title: {
                    display: true,
                    text: 'Request #',
                    color: '#475569',
                    font: { family: 'DM Sans', size: 11 }
                }
            },
            y: {
                grid:  { color: 'rgba(51,65,85,0.4)' },
                ticks: {
                    color: '#64748b',
                    font: { family: 'DM Sans' },
                    callback: val => val + 'ms'
                },
                beginAtZero: true,
                title: {
                    display: true,
                    text: 'Latency (ms)',
                    color: '#475569',
                    font: { family: 'DM Sans', size: 11 }
                }
            }
        }
    }
});

// ── Open SSE stream ─────────────────────────────────────────────────
// EventSource does not need CSRF — it is a GET request
const source = new EventSource(`/api/load-test/${testRunId}/stream`);

/**
 * onmessage fires for every regular SSE event (one per completed request).
 * Contract event shape: { index, latencyMs, statusCode, success, timestamp }
 */
source.onmessage = function (event) {
    const result = JSON.parse(event.data);

    // Update counters
    sentCount++;
    if (result.success === true) {
        successCount++;
    } else {
        failureCount++;
    }

    counterSentEl.textContent     = sentCount;
    counterSuccessEl.textContent  = successCount;
    counterFailuresEl.textContent = failureCount;

    // Push new data point to Chart.js line chart
    // x axis: request index from contract (result.index)
    // y axis: latency in ms (result.latencyMs)
    liveChart.data.labels.push(result.index);
    liveChart.data.datasets[0].data.push(result.latencyMs);
    liveChart.update('none'); // 'none' skips animation for performance
};

/**
 * done event fires once when the test completes.
 * This is a NAMED event — must use addEventListener, not onmessage.
 * Contract done event shape: { totalSent, successCount, failureCount, avgLatencyMs }
 *
 * Decisions doc rule: source.close() MUST be called here.
 */
source.addEventListener('done', function (event) {
    const summary = JSON.parse(event.data);

    // MANDATORY — close the stream (Decision 5 in decisions doc)
    source.close();

    // Update status badge to completed
    statusBadgeEl.className = 'badge-success';
    statusBadgeEl.innerHTML = 'Completed';

    // Populate final summary section using done event field names from contract
    summaryTotalSentEl.textContent  = summary.totalSent;
    summarySuccessEl.textContent    = summary.successCount;
    summaryFailuresEl.textContent   = summary.failureCount;
    summaryAvgLatencyEl.textContent = summary.avgLatencyMs + 'ms';

    // Set view results button href and show the summary section
    viewResultsBtnEl.href = `/load-test/${testRunId}/result`;
    finalSummaryEl.classList.remove('hidden');
});

/**
 * onerror fires when the SSE connection fails or drops.
 * Decision 4 rule: never silent — always show a message.
 */
source.onerror = function () {
    // Close the stream — do not leave hanging connection
    source.close();

    // Update status badge
    statusBadgeEl.className = 'badge-danger';
    statusBadgeEl.innerHTML = 'Connection lost';

    // Show inline error message
    showError('Lost connection to the server. The test may still be running. Check History for results.');
};

// ── Helper functions ────────────────────────────────────────────────

/**
 * Shows inline error message on the page.
 * Decision 4: never use alert() or silent console.log().
 * @param {string} message
 */
function showError(message) {
    liveErrorMsgEl.textContent = message;
    liveErrorEl.classList.remove('hidden');
}