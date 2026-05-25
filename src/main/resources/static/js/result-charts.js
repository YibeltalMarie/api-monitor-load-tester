/**
 * result-charts.js
 *
 * Handles Chart.js bar chart on load-test-result.html
 * This is the Group 2 addition to the result page.
 *
 * Responsibilities:
 *   1. Read testRunId from the current URL
 *   2. Fetch GET /api/load-test/{id}/results for raw individual results
 *   3. Bucket latencyMs values into 5 latency ranges
 *   4. Draw Chart.js bar chart showing request distribution
 *
 * Contract: GET /api/load-test/{id}/results
 * Response: Array of individual request results
 *   [
 *     { "index": 1,  "latencyMs": 120, "statusCode": 200, "success": true  },
 *     { "index": 2,  "latencyMs": 854, "statusCode": 503, "success": false },
 *     { "index": 3,  "latencyMs": 98,  "statusCode": 200, "success": true  }
 *   ]
 *
 * This file runs independently of load-test-result.js.
 * It makes its own fetch — does not depend on load-test-result.js finishing first.
 *
 * Error handling (Decision 4):
 *   Never silent. Show error message in chart area on failure.
 */

// ── Read testRunId from URL ──────────────────────────────────────────
// URL pattern: /load-test/{id}/result
// pathname.split('/') → ['', 'load-test', 'a1b2c3d4', 'result']
const chartTestRunId = window.location.pathname.split('/')[2];

// ── DOM element references ──────────────────────────────────────────
// These ids must exist in the updated load-test-result.html
const chartLoadingEl = document.getElementById('chart-loading');
const chartErrorEl   = document.getElementById('chart-error');
const chartErrorMsg  = document.getElementById('chart-error-msg');
const chartCanvas    = document.getElementById('latency-chart');

// ── Entry point ─────────────────────────────────────────────────────
loadChart();

/**
 * Fetches raw results and builds the distribution bar chart.
 */
async function loadChart() {
    showChartLoading(true);

    try {
        const response = await fetch(`/api/load-test/${chartTestRunId}/results`);

        if (!response.ok) {
            // Contract error shape: { status, error, message, timestamp }
            const err = await response.json();
            showChartError(err.message || 'Failed to load chart data.');
            return;
        }

        // Contract: array of { index, latencyMs, statusCode, success }
        const rawResults = await response.json();

        if (rawResults.length === 0) {
            showChartError('No result data available for this test.');
            return;
        }

        const buckets = bucketLatencies(rawResults);
        drawChart(buckets);

    } catch (err) {
        // Network error
        showChartError('Could not connect to the server to load chart data.');
        console.error('Chart fetch error:', err);
    } finally {
        showChartLoading(false);
    }
}

/**
 * Buckets raw results into 5 latency ranges.
 * Each result has a latencyMs value from the contract.
 *
 * Ranges:
 *   Bucket 0: 0   – 49ms   (fast)
 *   Bucket 1: 50  – 99ms
 *   Bucket 2: 100 – 199ms
 *   Bucket 3: 200 – 499ms
 *   Bucket 4: 500ms+       (slow)
 *
 * @param {Array} results - array of { index, latencyMs, statusCode, success }
 * @returns {number[]} array of 5 counts
 */
function bucketLatencies(results) {
    const buckets = [0, 0, 0, 0, 0];

    results.forEach(r => {
        if      (r.latencyMs <  50)  buckets[0]++;
        else if (r.latencyMs < 100)  buckets[1]++;
        else if (r.latencyMs < 200)  buckets[2]++;
        else if (r.latencyMs < 500)  buckets[3]++;
        else                         buckets[4]++;
    });

    return buckets;
}

/**
 * Draws the Chart.js bar chart with the bucketed data.
 * Colors: blue for fast buckets, yellow for medium, red for slow.
 * @param {number[]} buckets - array of 5 counts from bucketLatencies()
 */
function drawChart(buckets) {
    const ctx = chartCanvas.getContext('2d');

    new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['0–49ms', '50–99ms', '100–199ms', '200–499ms', '500ms+'],
            datasets: [{
                label: 'Requests',
                data: buckets,
                backgroundColor: [
                    'rgba(14,165,233,0.7)',   // blue  — fast
                    'rgba(14,165,233,0.7)',   // blue
                    'rgba(14,165,233,0.7)',   // blue
                    'rgba(234,179,8,0.7)',    // yellow — medium
                    'rgba(239,68,68,0.7)',    // red    — slow
                ],
                borderColor: [
                    'rgba(14,165,233,1)',
                    'rgba(14,165,233,1)',
                    'rgba(14,165,233,1)',
                    'rgba(234,179,8,1)',
                    'rgba(239,68,68,1)',
                ],
                borderWidth: 1,
                borderRadius: 4,
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: {
                    backgroundColor: '#1e293b',
                    borderColor: '#334155',
                    borderWidth: 1,
                    titleColor: '#e2e8f0',
                    bodyColor: '#94a3b8',
                    callbacks: {
                        label: ctx => `  ${ctx.parsed.y} requests`
                    }
                }
            },
            scales: {
                x: {
                    grid:  { color: 'rgba(51,65,85,0.4)' },
                    ticks: {
                        color: '#64748b',
                        font: { family: 'DM Sans' }
                    },
                    title: {
                        display: true,
                        text: 'Response Time Range',
                        color: '#475569',
                        font: { family: 'DM Sans', size: 11 }
                    }
                },
                y: {
                    grid:  { color: 'rgba(51,65,85,0.4)' },
                    ticks: {
                        color: '#64748b',
                        font: { family: 'DM Sans' }
                    },
                    beginAtZero: true,
                    title: {
                        display: true,
                        text: 'Number of Requests',
                        color: '#475569',
                        font: { family: 'DM Sans', size: 11 }
                    }
                }
            }
        }
    });
}

// ── State management ────────────────────────────────────────────────

/**
 * Shows or hides the loading spinner in the chart area.
 * @param {boolean} loading
 */
function showChartLoading(loading) {
    if (chartLoadingEl) {
        chartLoadingEl.classList.toggle('hidden', !loading);
    }
}

/**
 * Shows an error message in the chart area.
 * Decision 4: never silent — always visible to the user.
 * @param {string} message
 */
function showChartError(message) {
    if (chartErrorEl && chartErrorMsg) {
        chartErrorMsg.textContent = message;
        chartErrorEl.classList.remove('hidden');
    }
}