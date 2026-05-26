/**
 * monitor-detail.js
 *
 * Handles the monitor detail page on monitor-detail.html
 *
 * Responsibilities:
 *   1. Read endpointId from the current URL
 *   2. Fetch GET /api/monitor/{id}/results?hours=24 on page load
 *   3. Populate all stat cards from response
 *   4. Draw Chart.js line chart from results array
 *   5. Handle time window buttons (24h / 7d / 30d) — refetch and redraw
 *
 * Contract: GET /api/monitor/{id}/results?hours=24
 * Response fields used:
 *   endpointId, url, totalChecks, upCount, downCount,
 *   uptimePercent, avgLatencyMs
 *   results: [
 *     { checkedAt, isUp, statusCode, latencyMs }
 *   ]
 *   latencyMs: can be null when isUp is false
 *
 * Chart:
 *   x axis: checkedAt timestamps
 *   y axis: latencyMs (null → 0 when isUp is false)
 *   point color: blue when isUp=true, red when isUp=false
 *
 * Error handling (Decision 4):
 *   Never silent. Always show message on failure.
 */

// ── Read endpointId from URL ─────────────────────────────────────────
// URL pattern: /monitor/{id}
// pathname.split('/') → ['', 'monitor', '1']
const endpointId = window.location.pathname.split('/')[2];

// ── DOM element references ───────────────────────────────────────────
const loadingEl      = document.getElementById('detail-loading');
const errorEl        = document.getElementById('detail-error');
const errorMsgEl     = document.getElementById('detail-error-msg');
const contentEl      = document.getElementById('detail-content');

// Header
const detailUrlEl    = document.getElementById('detail-url');

// Stat cards
const statUptimeEl   = document.getElementById('stat-uptime');
const statChecksEl   = document.getElementById('stat-total-checks');
const statLatencyEl  = document.getElementById('stat-avg-latency');
const statDownEl     = document.getElementById('stat-down-count');

// Breakdown
const breakdownUpEl  = document.getElementById('breakdown-up');
const breakdownDownEl= document.getElementById('breakdown-down');

// Chart
const chartLoadingEl = document.getElementById('chart-loading');
const chartCanvas    = document.getElementById('latency-trend-chart');

// Time window buttons
const btn24h  = document.getElementById('btn-24h');
const btn7d   = document.getElementById('btn-7d');
const btn30d  = document.getElementById('btn-30d');

// ── Chart instance — kept in outer scope so we can destroy on redraw ──
let trendChart = null;

// ── Entry point ──────────────────────────────────────────────────────
init();

/**
 * Sets up time window button listeners and loads initial data.
 */
function init() {
    // Time window buttons map to hours values from contract
    // ?hours=24 | ?hours=168 | ?hours=720
    btn24h.addEventListener('click', () => {
        setActiveButton(btn24h);
        loadHistory(24);
    });
    btn7d.addEventListener('click', () => {
        setActiveButton(btn7d);
        loadHistory(168);
    });
    btn30d.addEventListener('click', () => {
        setActiveButton(btn30d);
        loadHistory(720);
    });

    // Load default: 24 hours
    loadHistory(24);
}

// ── Load history ─────────────────────────────────────────────────────

/**
 * Fetches GET /api/monitor/{id}/results?hours={hours}
 * Populates stats and redraws chart.
 * Called on page load and when time window button is clicked.
 * @param {number} hours - 24 | 168 | 720
 */
async function loadHistory(hours) {
    showLoading(true);

    try {
        const response = await fetch(
            `/api/monitor/${endpointId}/results?hours=${hours}`
        );

        if (!response.ok) {
            // Contract error shape: { status, error, message, timestamp }
            const err = await response.json();
            showError(err.message || 'Failed to load endpoint history.');
            return;
        }

        const data = await response.json();

        populateHeader(data);
        populateStats(data);
        populateBreakdown(data);
        drawChart(data.results);

        // Show main content
        contentEl.classList.remove('hidden');

    } catch (err) {
        showError('Could not connect to the server.');
        console.error('Monitor detail fetch error:', err);
    } finally {
        showLoading(false);
    }
}

// ── Populate functions ───────────────────────────────────────────────

/**
 * Fills the page header URL.
 * Uses: data.url
 * @param {object} data
 */
function populateHeader(data) {
    detailUrlEl.textContent = data.url;
}

/**
 * Fills all stat cards.
 * Uses exact field names from contract:
 *   uptimePercent, totalChecks, avgLatencyMs, downCount
 * @param {object} data
 */
function populateStats(data) {
    statUptimeEl.textContent = data.uptimePercent != null
    ? data.uptimePercent.toFixed(1) + '%'
    : '—';
    statChecksEl.textContent  = data.totalChecks;
    statLatencyEl.textContent = data.avgLatencyMs != null
        ? data.avgLatencyMs + 'ms'
        : '—';
    statDownEl.textContent    = data.downCount;

    // Color uptime based on value
    statUptimeEl.className = 'stat-number ' + uptimeColor(data.uptimePercent);
}

/**
 * Fills up/down breakdown section.
 * Uses: data.upCount, data.downCount
 * @param {object} data
 */
function populateBreakdown(data) {
    breakdownUpEl.textContent   = data.upCount;
    breakdownDownEl.textContent = data.downCount;
}

/**
 * Draws or redraws the Chart.js line chart.
 * Uses results array from contract:
 *   each item: { checkedAt, isUp, statusCode, latencyMs }
 *   latencyMs can be null when isUp is false → shown as 0
 *
 * Points where isUp=false are colored red.
 * Points where isUp=true  are colored blue.
 * @param {Array} results
 */
function drawChart(results) {
    chartLoadingEl.classList.add('hidden');

    // Destroy previous chart instance if exists
    // This prevents Chart.js canvas reuse error on time window switch
    if (trendChart) {
        trendChart.destroy();
        trendChart = null;
    }

    if (!results || results.length === 0) {
        chartLoadingEl.textContent = 'No check data available for this period.';
        chartLoadingEl.classList.remove('hidden');
        return;
    }

    // Build chart data from results array
    const labels     = results.map(r => formatTimestamp(r.checkedAt));
    const dataPoints = results.map(r => r.latencyMs != null ? r.latencyMs : 0);

    // Per-point colors: blue when up, red when down
    // Contract field: isUp (boolean)
    const pointColors = results.map(r =>
        r.isUp ? 'rgba(14,165,233,0.8)' : 'rgba(239,68,68,0.8)'
    );
    const pointBorderColors = results.map(r =>
        r.isUp ? 'rgba(14,165,233,1)' : 'rgba(239,68,68,1)'
    );

    const ctx = chartCanvas.getContext('2d');
    trendChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: labels,
            datasets: [{
                label: 'Latency (ms)',
                data: dataPoints,
                borderColor: '#0ea5e9',
                backgroundColor: 'rgba(14,165,233,0.05)',
                borderWidth: 2,
                pointRadius: 3,
                pointHoverRadius: 5,
                pointBackgroundColor: pointColors,
                pointBorderColor: pointBorderColors,
                tension: 0.3,
                fill: true,
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
                        // Show status code in tooltip
                        // Contract result field: statusCode
                        afterLabel: (ctx) => {
                            const result = results[ctx.dataIndex];
                            const status = result.isUp ? 'UP' : 'DOWN';
                            return `  Status: ${result.statusCode} (${status})`;
                        },
                        label: (ctx) => `  ${ctx.parsed.y}ms`
                    }
                }
            },
            scales: {
                x: {
                    grid:  { color: 'rgba(51,65,85,0.4)' },
                    ticks: {
                        color: '#64748b',
                        font: { family: 'DM Sans' },
                        maxTicksLimit: 8,
                        maxRotation: 0
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
}

// ── Time window button helpers ────────────────────────────────────────

/**
 * Highlights the active time window button.
 * Removes active style from all buttons, adds to selected.
 * @param {HTMLElement} activeBtn
 */
function setActiveButton(activeBtn) {
    [btn24h, btn7d, btn30d].forEach(btn => {
        btn.className = 'px-3 py-1.5 rounded-md text-xs font-medium ' +
            'text-slate-400 hover:text-white hover:bg-white/5 ' +
            'border border-surface-border transition-all';
    });
    activeBtn.className = 'px-3 py-1.5 rounded-md text-xs font-medium ' +
        'bg-brand-500 text-white transition-all';
}

// ── Utility functions ─────────────────────────────────────────────────

/**
 * Returns color class based on uptime percentage.
 * @param {number} pct
 * @returns {string}
 */
function uptimeColor(pct) {
    if (pct >= 99) return 'stat-number text-green-400';
    if (pct >= 95) return 'stat-number text-yellow-400';
    return 'stat-number text-red-400';
}

/**
 * Formats ISO 8601 timestamp for chart x axis labels.
 * Contract: checkedAt is "2026-05-17T10:29:00"
 * Output: "10:29" — short form for chart labels
 * @param {string} isoString
 * @returns {string}
 */
function formatTimestamp(isoString) {
    if (!isoString) return '—';
    // Show time only for 24h view — short enough for chart labels
    return isoString.substring(11, 16);
}

// ── State management ──────────────────────────────────────────────────

function showLoading(loading) {
    if (loading) {
        loadingEl.classList.remove('hidden');
        contentEl.classList.add('hidden');
        errorEl.classList.add('hidden');
    } else {
        loadingEl.classList.add('hidden');
    }
}

/**
 * Shows error state.
 * Decision 4: never silent.
 * @param {string} message
 */
function showError(message) {
    loadingEl.classList.add('hidden');
    errorMsgEl.textContent = message;
    errorEl.classList.remove('hidden');
}