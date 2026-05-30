/**
 * monitor-list.js
 *
 * Handles the monitor page on monitor-list.html
 *
 * This page shows ONLY the add endpoint form.
 * The endpoints list lives on load-test-history.html (Monitored APIs tab).
 *
 * Responsibilities:
 *   1. Show/hide request body and content-type fields based on method
 *   2. Handle form submit → POST /api/monitor → redirect to /monitor/{id}
 *
 * Contract: POST /api/monitor
 * Request body:
 *   {
 *     url:                string,
 *     method:             string  (GET | POST | PUT | DELETE),
 *     intervalSeconds:    int,
 *     expectedStatusCode: int,
 *     enabled:            boolean,
 *     alertEmail:         string | null  ← NEW field
 *   }
 *
 * alertEmail rules:
 *   - If user fills the field → send the value as string
 *   - If user leaves it empty → send null
 *   - Dev B sends alert email to this address when endpoint goes DOWN
 *   - Dev B sends recovery email when endpoint comes back UP
 *   - Dev B skips email entirely when alertEmail is null
 *
 * Response 201: { id, url, method, ... }
 *   id used to redirect to /monitor/{id}
 *
 * Error handling (Decision 4): never silent
 * CSRF: POST reads token from base.html meta tags
 */

// ── CSRF helpers ──────────────────────────────────────────────────────
function getCsrfToken() {
    return document.querySelector('meta[name="_csrf"]')?.content;
}
function getCsrfHeader() {
    return document.querySelector('meta[name="_csrf_header"]')?.content;
}

function authHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    const token  = getCsrfToken();
    const header = getCsrfHeader();
    if (token && header) headers[header] = token;
    return headers;
}

// ── DOM element references ─────────────────────────────────────────────
const addForm         = document.getElementById('add-form');
const addUrlEl        = document.getElementById('add-url');
const addMethodEl     = document.getElementById('add-method');
const addStatusEl     = document.getElementById('add-expected-status');
const addIntervalEl   = document.getElementById('add-interval');
const addEnabledEl    = document.getElementById('add-enabled');
const addAlertEmailEl = document.getElementById('add-alert-email'); // NEW
const addSubmitBtn    = document.getElementById('add-submit-btn');
const addBtnIcon      = document.getElementById('add-btn-icon');
const addBtnSpinner   = document.getElementById('add-btn-spinner');
const addBtnText      = document.getElementById('add-btn-text');
const addErrorBox     = document.getElementById('add-error-box');
const bodySection     = document.getElementById('body-section');
const bodyInput       = document.getElementById('add-request-body');
const contentTypeEl   = document.getElementById('add-content-type');

// ── Show/hide body fields based on method ──────────────────────────────
// GET and DELETE → no body needed
// POST and PUT   → show body textarea and content-type field
addMethodEl.addEventListener('change', function () {
    if (this.value === 'POST' || this.value === 'PUT') {
        bodySection.classList.remove('hidden');
    } else {
        bodySection.classList.add('hidden');
        if (bodyInput)     bodyInput.value = '';
        if (contentTypeEl) contentTypeEl.value = 'application/json';
    }
});

// ── Form submit ────────────────────────────────────────────────────────
addForm.addEventListener('submit', handleAddSubmit);

/**
 * Handles add form submit.
 * POSTs to /api/monitor with JSON body.
 * On 201 → redirects to /monitor/{id}.
 *
 * Contract request body field names used exactly:
 *   url, method, intervalSeconds, expectedStatusCode, enabled, alertEmail
 *
 * alertEmail: string value if filled, null if empty
 */
async function handleAddSubmit(e) {
    e.preventDefault();
    clearAddError();
    showAddSpinner(true);

    // Read alertEmail — send null if empty per contract
    const alertEmailValue = addAlertEmailEl.value.trim();

    const payload = {
        url:                addUrlEl.value.trim(),
        method:             addMethodEl.value,
        intervalSeconds:    parseInt(addIntervalEl.value),
        expectedStatusCode: parseInt(addStatusEl.value),
        enabled:            addEnabledEl.checked,
        alertEmail:         alertEmailValue || null  // null if empty
    };

    try {
        const response = await fetch('/api/monitor', {
            method: 'POST',
            headers: authHeaders(),
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            // Contract error shape: { status, error, message, timestamp }
            const err = await response.json();
            showAddError(err.message || 'Failed to add endpoint.');
            return;
        }

        // Contract: 201 response contains id of newly created endpoint
        const newEndpoint = await response.json();

        // Redirect to detail page of newly created endpoint
        window.location.href = `/monitor/${newEndpoint.id}`;

    } catch (err) {
        showAddError('Could not connect to the server.');
        console.error('Add endpoint error:', err);
    } finally {
        showAddSpinner(false);
    }
}

// ── Helpers ────────────────────────────────────────────────────────────

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