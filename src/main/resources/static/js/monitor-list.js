/**
 * monitor-list.js
 *
 * Handles the monitor list page on monitor-list.html
 *
 * Responsibilities:
 *   1. Fetch GET /api/monitor → render all endpoint rows
 *   2. Handle add form submit → POST /api/monitor → append new row
 *   3. Handle toggle switch   → PATCH /api/monitor/{id}/toggle → update row
 *   4. Handle delete button   → DELETE /api/monitor/{id} → remove row
 *
 * Contract: GET /api/monitor
 * Response array fields used:
 *   id, url, method, intervalSeconds, expectedStatusCode,
 *   enabled, currentStatus, lastCheckedAt, lastLatencyMs, uptimePercent24h
 *   currentStatus values: "UP" | "DOWN" | "UNKNOWN"
 *   lastLatencyMs: can be null (never checked yet)
 *
 * Contract: POST /api/monitor
 * Request body: { url, method, intervalSeconds, expectedStatusCode, enabled }
 * Response 201: same shape as one item above
 *
 * Contract: PATCH /api/monitor/{id}/toggle
 * Response 200: { id, enabled }
 *
 * Contract: DELETE /api/monitor/{id}
 * Response 204: no body
 *
 * Error handling (Decision 4):
 *   Never silent. Always show message to user.
 *   4xx → inline error near the action that caused it
 *
 * CSRF: all POST, PATCH, DELETE read token from base.html meta tags
 */

// ── CSRF helpers ────────────────────────────────────────────────────
function getCsrfToken() {
    return document.querySelector('meta[name="_csrf"]')?.content;
}
function getCsrfHeader() {
    return document.querySelector('meta[name="_csrf_header"]')?.content;
}

/**
 * Builds fetch headers with CSRF for mutating requests.
 * @param {boolean} hasBody - true for POST, false for PATCH/DELETE with no body
 * @returns {object}
 */
function authHeaders(hasBody = false) {
    const headers = {};
    const token  = getCsrfToken();
    const header = getCsrfHeader();
    if (token && header) headers[header] = token;
    if (hasBody) headers['Content-Type'] = 'application/json';
    return headers;
}

// ── DOM element references ──────────────────────────────────────────
const loadingEl      = document.getElementById('monitor-loading');
const errorEl        = document.getElementById('monitor-error');
const errorMsgEl     = document.getElementById('monitor-error-msg');
const emptyEl        = document.getElementById('monitor-empty');
const tableWrapperEl = document.getElementById('monitor-table-wrapper');
const monitorBodyEl  = document.getElementById('monitor-body');

// Add form elements
const addForm        = document.getElementById('add-form');
const addUrlEl       = document.getElementById('add-url');
const addMethodEl    = document.getElementById('add-method');
const addStatusEl    = document.getElementById('add-expected-status');
const addIntervalEl  = document.getElementById('add-interval');
const addEnabledEl   = document.getElementById('add-enabled');
const addSubmitBtn   = document.getElementById('add-submit-btn');
const addBtnIcon     = document.getElementById('add-btn-icon');
const addBtnSpinner  = document.getElementById('add-btn-spinner');
const addBtnText     = document.getElementById('add-btn-text');
const addErrorBox    = document.getElementById('add-error-box');

// ── Entry point ─────────────────────────────────────────────────────
loadEndpoints();
addForm.addEventListener('submit', handleAddSubmit);

// ── Load all endpoints ──────────────────────────────────────────────

/**
 * Fetches GET /api/monitor and renders the endpoint list.
 * Contract: always returns [] not null when no endpoints exist.
 */
async function loadEndpoints() {
    showState('loading');

    try {
        const response = await fetch('/api/monitor');

        if (!response.ok) {
            const err = await response.json();
            showState('error', err.message || 'Failed to load endpoints.');
            return;
        }

        // Contract: array, never null
        const endpoints = await response.json();

        if (endpoints.length === 0) {
            showState('empty');
            return;
        }

        monitorBodyEl.innerHTML = '';
        endpoints.forEach(ep => {
            const row = buildRow(ep);
            monitorBodyEl.appendChild(row);
        });

        showState('table');

    } catch (err) {
        showState('error', 'Could not connect to the server.');
        console.error('Monitor load error:', err);
    }
}

// ── Build one table row ─────────────────────────────────────────────

/**
 * Creates a <tr> element from one endpoint object.
 * Uses exact field names from the contract:
 *   id, url, method, intervalSeconds, uptimePercent24h,
 *   lastLatencyMs, lastCheckedAt, currentStatus, enabled
 * @param {object} ep - one MonitoredEndpointStatus from contract
 * @returns {HTMLElement}
 */
function buildRow(ep) {
    const tr = document.createElement('tr');
    tr.setAttribute('data-id', ep.id);

    tr.innerHTML = `
        <td>
            <a href="/monitor/${ep.id}"
               class="mono text-brand-400 hover:text-brand-300
                      transition-colors text-sm">
                ${escapeHtml(ep.url)}
            </a>
            <span class="ml-2 badge-info">${escapeHtml(ep.method)}</span>
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
        <td class="text-slate-500 text-xs">
            ${formatTimestamp(ep.lastCheckedAt)}
        </td>
        <td>${statusBadge(ep.currentStatus)}</td>
        <td>
            <!--
                Toggle checkbox:
                data-id used by toggleEndpoint() to find endpoint id
                checked state reflects ep.enabled from contract
            -->
            <label class="flex items-center cursor-pointer">
                <input type="checkbox"
                       class="toggle-checkbox w-4 h-4 accent-brand-500"
                       data-id="${ep.id}"
                       ${ep.enabled ? 'checked' : ''}/>
                <span class="ml-2 text-xs text-slate-500">
                    ${ep.enabled ? 'On' : 'Off'}
                </span>
            </label>
        </td>
        <td>
            <button class="delete-btn text-xs text-slate-500
                           hover:text-red-400 transition-colors"
                    data-id="${ep.id}">
                Delete
            </button>
        </td>
    `;

    // Attach toggle listener
    const toggleEl = tr.querySelector('.toggle-checkbox');
    toggleEl.addEventListener('change', () => toggleEndpoint(ep.id, tr, toggleEl));

    // Attach delete listener
    const deleteEl = tr.querySelector('.delete-btn');
    deleteEl.addEventListener('click', () => deleteEndpoint(ep.id, tr));

    return tr;
}

// ── Add endpoint ────────────────────────────────────────────────────

/**
 * Handles add form submit.
 * POSTs to /api/monitor with JSON body.
 * On 201 → appends new row to table, no page reload.
 * Contract request body: { url, method, intervalSeconds,
 *                          expectedStatusCode, enabled }
 */
async function handleAddSubmit(e) {
    e.preventDefault();
    clearAddError();
    showAddSpinner(true);

    const payload = {
        url:                addUrlEl.value.trim(),
        method:             addMethodEl.value,
        intervalSeconds:    parseInt(addIntervalEl.value),
        expectedStatusCode: parseInt(addStatusEl.value),
        enabled:            addEnabledEl.checked
    };

    try {
        const response = await fetch('/api/monitor', {
            method: 'POST',
            headers: authHeaders(true),
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            // Contract error shape: { status, error, message, timestamp }
            const err = await response.json();
            showAddError(err.message || 'Failed to add endpoint.');
            return;
        }

        // Contract: 201 response is same shape as GET /api/monitor item
        const newEndpoint = await response.json();

        // Reset form
        addForm.reset();
        addEnabledEl.checked = true;

        // If table was hidden (empty state), switch to table view
        if (tableWrapperEl.classList.contains('hidden')) {
            emptyEl.classList.add('hidden');
            tableWrapperEl.classList.remove('hidden');
        }

        // Append new row to table
        const row = buildRow(newEndpoint);
        monitorBodyEl.appendChild(row);

    } catch (err) {
        showAddError('Could not connect to the server.');
        console.error('Add endpoint error:', err);
    } finally {
        showAddSpinner(false);
    }
}

// ── Toggle endpoint ─────────────────────────────────────────────────

/**
 * PATCHes /api/monitor/{id}/toggle.
 * Contract response: { id, enabled }
 * On success → updates the label text in the row.
 * On failure → reverts the checkbox state.
 * @param {number} id
 * @param {HTMLElement} row
 * @param {HTMLElement} checkbox
 */
async function toggleEndpoint(id, row, checkbox) {
    const previousState = !checkbox.checked;

    try {
        const response = await fetch(`/api/monitor/${id}/toggle`, {
            method: 'PATCH',
            headers: authHeaders(false)
        });

        if (!response.ok) {
            // Revert checkbox if toggle failed
            checkbox.checked = previousState;
            const err = await response.json();
            showAddError(err.message || 'Failed to toggle endpoint.');
            return;
        }

        // Contract response: { id, enabled }
        const result = await response.json();

        // Update label text next to checkbox
        const label = checkbox.nextElementSibling;
        if (label) {
            label.textContent = result.enabled ? 'On' : 'Off';
        }

    } catch (err) {
        checkbox.checked = previousState;
        showAddError('Could not connect to the server.');
        console.error('Toggle error:', err);
    }
}

// ── Delete endpoint ─────────────────────────────────────────────────

/**
 * DELETEs /api/monitor/{id}.
 * Contract response: 204 No Content — no body.
 * On success → removes the row from DOM.
 * If no rows remain → shows empty state.
 * @param {number} id
 * @param {HTMLElement} row
 */
async function deleteEndpoint(id, row) {
    try {
        const response = await fetch(`/api/monitor/${id}`, {
            method: 'DELETE',
            headers: authHeaders(false)
        });

        // Contract: 204 No Content on success
        if (response.status !== 204) {
            const err = await response.json();
            showAddError(err.message || 'Failed to delete endpoint.');
            return;
        }

        // Remove row from DOM
        row.remove();

        // If no rows remain, show empty state
        if (monitorBodyEl.children.length === 0) {
            tableWrapperEl.classList.add('hidden');
            emptyEl.classList.remove('hidden');
        }

    } catch (err) {
        showAddError('Could not connect to the server.');
        console.error('Delete error:', err);
    }
}

// ── Render helpers ──────────────────────────────────────────────────

/**
 * Returns status badge HTML.
 * Contract currentStatus values: "UP" | "DOWN" | "UNKNOWN"
 * @param {string} status
 * @returns {string}
 */
function statusBadge(status) {
    if (status === 'UP')      return '<span class="badge-success">UP</span>';
    if (status === 'DOWN')    return '<span class="badge-danger">DOWN</span>';
    return '<span class="badge-info">UNKNOWN</span>';
}

/**
 * Returns color class based on uptime percentage.
 * @param {number|null} pct
 * @returns {string}
 */
function uptimeColor(pct) {
    if (pct == null)   return 'text-slate-500';
    if (pct >= 99)     return 'text-green-400';
    if (pct >= 95)     return 'text-yellow-400';
    return 'text-red-400';
}

/**
 * Formats ISO 8601 timestamp.
 * Contract: timestamps are "2026-05-17T10:29:00"
 * lastCheckedAt can be null if endpoint was never checked.
 * @param {string|null} isoString
 * @returns {string}
 */
function formatTimestamp(isoString) {
    if (!isoString) return 'Never';
    return isoString.substring(0, 16).replace('T', ' ');
}

/**
 * Escapes HTML to prevent XSS when injecting user data into innerHTML.
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

/**
 * Shows one of four states: loading | error | empty | table
 * @param {string} state
 * @param {string} [message] - only used for error state
 */
function showState(state, message = '') {
    loadingEl.classList.add('hidden');
    errorEl.classList.add('hidden');
    emptyEl.classList.add('hidden');
    tableWrapperEl.classList.add('hidden');

    if (state === 'loading') loadingEl.classList.remove('hidden');
    if (state === 'error')   { errorMsgEl.textContent = message; errorEl.classList.remove('hidden'); }
    if (state === 'empty')   emptyEl.classList.remove('hidden');
    if (state === 'table')   tableWrapperEl.classList.remove('hidden');
}

function showAddError(message) {
    addErrorBox.textContent = message;
    addErrorBox.classList.remove('hidden');
}

function clearAddError() {
    addErrorBox.classList.add('hidden');
    addErrorBox.textContent = '';
}

function showAddSpinner(loading) {
    addSubmitBtn.disabled = loading;
    addBtnIcon.classList.toggle('hidden', loading);
    addBtnSpinner.classList.toggle('hidden', !loading);
    addBtnText.textContent = loading ? 'Adding...' : 'Add Endpoint';
}