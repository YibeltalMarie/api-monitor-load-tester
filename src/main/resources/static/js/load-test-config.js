/**
 * load-test-config.js
 *
 * Handles the load test configuration form on load-test-config.html
 *
 * Responsibilities:
 *   1. Show/hide request body textarea based on selected HTTP method
 *   2. Listen for form submit
 *   3. Read and validate all form field values
 *   4. Parse optional headers JSON
 *   5. POST to /api/load-test/run with CSRF token
 *   6. On success → redirect to /load-test/{testRunId}/live
 *   7. On failure → show inline error message
 *
 * Contract: POST /api/load-test/run
 * Request body:
 *   {
 *     url:            string,
 *     method:         string  (GET | POST | PUT | DELETE),
 *     totalRequests:  int,
 *     concurrency:    int,
 *     timeoutSeconds: int,
 *     requestBody:    string | null,
 *     headers:        object
 *   }
 * Response: { "testRunId": "uuid-string" }
 *
 * Error response shape:
 *   { "status": 400, "error": "...", "message": "...", "timestamp": "..." }
 */

// ── DOM element references ──────────────────────────────────────────
const form         = document.getElementById('run-form');
const urlInput     = document.getElementById('url');
const methodSelect = document.getElementById('method');
const totalReqInput= document.getElementById('totalRequests');
const concurrencyInput = document.getElementById('concurrency');
const timeoutInput = document.getElementById('timeoutSeconds');
const headersInput = document.getElementById('headers');
const bodyInput    = document.getElementById('requestBody');
const bodySection  = document.getElementById('body-section');
const submitBtn    = document.getElementById('submit-btn');
const btnIcon      = document.getElementById('btn-icon');
const btnSpinner   = document.getElementById('btn-spinner');
const btnText      = document.getElementById('btn-text');
const errorBox     = document.getElementById('error-box');

// ── Show/hide request body based on HTTP method ─────────────────────
// Body only makes sense for POST and PUT
methodSelect.addEventListener('change', function () {
    if (this.value === 'POST' || this.value === 'PUT') {
        bodySection.classList.remove('hidden');
    } else {
        bodySection.classList.add('hidden');
        bodyInput.value = '';
    }
});

// ── Form submit handler ─────────────────────────────────────────────
form.addEventListener('submit', async function (e) {
    e.preventDefault();  // stop browser default form POST

    clearError();
    showSpinner(true);

    // Parse headers textarea — must be valid JSON if provided
    const headersValue = headersInput.value.trim();
    let parsedHeaders = {};
    if (headersValue) {
        try {
            parsedHeaders = JSON.parse(headersValue);
        } catch {
            showError('Headers must be valid JSON. Example: {"Authorization": "Bearer token"}');
            showSpinner(false);
            return;
        }
    }

    // Build request payload — field names match contract exactly
    const payload = {
        url:            urlInput.value.trim(),
        method:         methodSelect.value,
        totalRequests:  parseInt(totalReqInput.value),
        concurrency:    parseInt(concurrencyInput.value),
        timeoutSeconds: parseInt(timeoutInput.value),
        requestBody:    bodyInput.value.trim() || null,
        headers:        parsedHeaders
    };

    // Read CSRF token from meta tags injected by base.html
    // These tags are added by Thymeleaf when Spring Security is active
    const csrfToken  = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

    try {
        const response = await fetch('/api/load-test/run', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                // CSRF header name is dynamic — Spring Security decides the name
                // csrfHeader is typically "X-CSRF-TOKEN"
                ...(csrfHeader && csrfToken ? { [csrfHeader]: csrfToken } : {})
            },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            // Contract error shape: { status, error, message, timestamp }
            const err = await response.json();
            showError(err.message || 'Something went wrong. Please try again.');
            return;
        }

        // Contract success response: { "testRunId": "uuid" }
        const data = await response.json();
        window.location.href = `/load-test/${data.testRunId}/live`;

    } catch (err) {
        // Network error — server not reachable
        showError('Could not connect to the server. Please check it is running.');
        console.error('Fetch error:', err);
    } finally {
        showSpinner(false);
    }
});

// ── Helper functions ────────────────────────────────────────────────

/**
 * Shows or hides the loading spinner on the submit button.
 * @param {boolean} loading
 */
function showSpinner(loading) {
    submitBtn.disabled = loading;
    btnIcon.classList.toggle('hidden', loading);
    btnSpinner.classList.toggle('hidden', !loading);
    btnText.textContent = loading ? 'Starting...' : 'Run Test';
}

/**
 * Shows an inline error message above the form.
 * @param {string} message
 */
function showError(message) {
    errorBox.textContent = message;
    errorBox.classList.remove('hidden');
    errorBox.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

/**
 * Hides and clears the inline error message.
 */
function clearError() {
    errorBox.classList.add('hidden');
    errorBox.textContent = '';
}