// ============================================
// SMS Reader — Dashboard JavaScript
// ============================================

const API_BASE = '';
const REFRESH_INTERVAL = 5000; // 5 seconds
const STATUS_INTERVAL = 30000; // 30 seconds

let authToken = null;
let refreshTimer = null;
let statusTimer = null;
let knownMessageIds = new Set();
let isFirstLoad = true;

// ============================================
// DOM Elements
// ============================================
const $ = (id) => document.getElementById(id);

const loginScreen = $('login-screen');
const dashboardScreen = $('dashboard-screen');
const loginForm = $('login-form');
const passwordInput = $('password-input');
const loginBtn = $('login-btn');
const loginError = $('login-error');
const logoutBtn = $('logout-btn');
const searchInput = $('search-input');
const clearSearchBtn = $('clear-search');
const filterInfo = $('filter-info');
const messagesList = $('messages-list');
const emptyState = $('empty-state');
const loadingState = $('loading-state');
const msgCount = $('msg-count');
const uptimeDisplay = $('uptime-display');
const statusDot = $('status-dot');
const statusText = $('status-text');
const expiryMinutes = $('expiry-minutes');
const toast = $('toast');

// ============================================
// Initialization
// ============================================
(function init() {
  // Check for saved session
  const savedToken = localStorage.getItem('sms_reader_token');
  if (savedToken) {
    authToken = savedToken;
    showDashboard();
  }

  // Event listeners
  loginForm.addEventListener('submit', handleLogin);
  logoutBtn.addEventListener('click', handleLogout);
  searchInput.addEventListener('input', debounce(handleSearch, 300));
  clearSearchBtn.addEventListener('click', () => {
    searchInput.value = '';
    clearSearchBtn.style.display = 'none';
    filterInfo.textContent = '';
    fetchMessages();
  });
})();

// ============================================
// Auth
// ============================================
async function handleLogin(e) {
  e.preventDefault();
  const password = passwordInput.value.trim();
  if (!password) return;

  loginBtn.disabled = true;
  loginBtn.querySelector('.btn-text').style.display = 'none';
  loginBtn.querySelector('.btn-loader').style.display = 'inline-block';
  loginError.textContent = '';

  try {
    const res = await fetch(`${API_BASE}/api/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password }),
    });

    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.error || 'Login failed');
    }

    authToken = data.token;
    localStorage.setItem('sms_reader_token', authToken);
    showDashboard();
  } catch (err) {
    loginError.textContent = err.message;
    passwordInput.focus();
  } finally {
    loginBtn.disabled = false;
    loginBtn.querySelector('.btn-text').style.display = '';
    loginBtn.querySelector('.btn-loader').style.display = 'none';
  }
}

function handleLogout() {
  authToken = null;
  localStorage.removeItem('sms_reader_token');
  knownMessageIds.clear();
  isFirstLoad = true;

  if (refreshTimer) clearInterval(refreshTimer);
  if (statusTimer) clearInterval(statusTimer);

  dashboardScreen.classList.remove('active');
  loginScreen.classList.add('active');
  passwordInput.value = '';
  loginError.textContent = '';
  messagesList.innerHTML = '';
}

function showDashboard() {
  loginScreen.classList.remove('active');
  dashboardScreen.classList.add('active');
  loadingState.style.display = '';
  emptyState.style.display = 'none';

  fetchMessages();
  fetchStatus();

  refreshTimer = setInterval(fetchMessages, REFRESH_INTERVAL);
  statusTimer = setInterval(fetchStatus, STATUS_INTERVAL);
}

// ============================================
// Data Fetching
// ============================================
async function fetchMessages() {
  const search = searchInput.value.trim();

  try {
    const url = new URL(`${window.location.origin}/api/messages`);
    url.searchParams.set('token', authToken);
    if (search) url.searchParams.set('search', search);

    const res = await fetch(url.toString());

    if (res.status === 401) {
      handleLogout();
      loginError.textContent = 'Session expired — please login again';
      return;
    }

    const data = await res.json();

    if (data.expiryMinutes && expiryMinutes) {
      expiryMinutes.textContent = data.expiryMinutes;
    }

    renderMessages(data.messages);
    msgCount.textContent = data.total;

    if (search && data.total !== data.filtered) {
      filterInfo.textContent = `Showing ${data.filtered} of ${data.total} messages`;
    } else {
      filterInfo.textContent = '';
    }

    loadingState.style.display = 'none';
    statusDot.classList.add('online');
    statusDot.classList.remove('offline');
    statusText.textContent = 'Connected';
  } catch (err) {
    statusDot.classList.remove('online');
    statusDot.classList.add('offline');
    statusText.textContent = 'Connection lost';
  }
}

async function fetchStatus() {
  try {
    const res = await fetch(`${API_BASE}/api/status`);
    const data = await res.json();
    uptimeDisplay.textContent = formatUptime(data.uptime);
  } catch (err) {
    uptimeDisplay.textContent = '—';
  }
}

// ============================================
// Rendering
// ============================================
function renderMessages(msgs) {
  if (!msgs || msgs.length === 0) {
    messagesList.innerHTML = '';
    emptyState.style.display = '';
    return;
  }

  emptyState.style.display = 'none';

  // Find new messages
  const newIds = new Set();
  msgs.forEach((m) => newIds.add(m.id));

  const html = msgs
    .map((msg, index) => {
      const isNew = !isFirstLoad && !knownMessageIds.has(msg.id);
      const senderInitial = getSenderInitial(msg.sender);
      const timeStr = formatTime(msg.timestamp);
      const expiryStr = formatExpiry(msg.expiresAt);
      const isExpiringSoon = (msg.expiresAt - Date.now()) < 10 * 60 * 1000;

      return `
        <div class="message-card ${isNew ? 'new' : ''}" style="animation-delay: ${index * 0.05}s" data-id="${msg.id}">
          <div class="message-header">
            <div class="sender-info">
              <div class="sender-avatar">${senderInitial}</div>
              <div>
                <div class="sender-name">${escapeHtml(msg.sender)}</div>
                <div class="sender-time">${timeStr}</div>
              </div>
            </div>
            <div class="message-actions">
              <button class="action-btn" onclick="copyMessage('${msg.id}')" title="Copy message">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                  <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                </svg>
              </button>
            </div>
          </div>
          <div class="message-body">${escapeHtml(msg.message)}</div>
          <div class="message-footer">
            <span class="expiry-badge ${isExpiringSoon ? 'expiring-soon' : ''}">
              <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
              </svg>
              ${expiryStr}
            </span>
          </div>
        </div>
      `;
    })
    .join('');

  messagesList.innerHTML = html;

  // Update known IDs
  knownMessageIds = newIds;
  isFirstLoad = false;
}

// ============================================
// Actions
// ============================================
// Store message data for copy
let messageDataCache = {};

function renderMessages_orig(msgs) {
  // This is replaced by the above, but we keep a cache of message data
  msgs.forEach((m) => (messageDataCache[m.id] = m.message));
}

// Override: also cache data in the real render
const _origRender = renderMessages;
// We patch via the render function itself - cache is built inline

window.copyMessage = async function (id) {
  // Find the message card and get its body text
  const card = document.querySelector(`[data-id="${id}"]`);
  if (!card) return;

  const bodyEl = card.querySelector('.message-body');
  const text = bodyEl ? bodyEl.textContent : '';

  try {
    await navigator.clipboard.writeText(text);
    const btn = card.querySelector('.action-btn');
    if (btn) {
      btn.classList.add('copied');
      setTimeout(() => btn.classList.remove('copied'), 1500);
    }
    showToast('Message copied to clipboard', 'success');
  } catch (err) {
    showToast('Failed to copy', 'error');
  }
};

function handleSearch() {
  const val = searchInput.value.trim();
  clearSearchBtn.style.display = val ? '' : 'none';
  fetchMessages();
}

// ============================================
// Utilities
// ============================================
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

function getSenderInitial(sender) {
  if (!sender) return '?';
  // If it's a phone number, use last 2 digits
  const digits = sender.replace(/\D/g, '');
  if (digits.length >= 2) {
    return digits.slice(-2);
  }
  // Otherwise use first letter
  return sender.charAt(0).toUpperCase();
}

function formatTime(timestamp) {
  const date = new Date(timestamp);
  const now = new Date();
  const diffMs = now - date;
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);

  if (diffMins < 1) return 'Just now';
  if (diffMins < 60) return `${diffMins}m ago`;
  if (diffHours < 24) return `${diffHours}h ago`;

  return date.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatExpiry(expiresAt) {
  const remaining = expiresAt - Date.now();
  if (remaining <= 0) return 'Expired';

  const mins = Math.floor(remaining / 60000);
  if (mins < 60) return `Expires in ${mins}m`;

  const hours = Math.floor(mins / 60);
  const remMins = mins % 60;
  return `Expires in ${hours}h ${remMins}m`;
}

function formatUptime(seconds) {
  if (!seconds && seconds !== 0) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function showToast(message, type = '') {
  toast.textContent = message;
  toast.className = `toast show ${type}`;
  setTimeout(() => {
    toast.className = 'toast';
  }, 2500);
}

function debounce(fn, ms) {
  let timer;
  return function (...args) {
    clearTimeout(timer);
    timer = setTimeout(() => fn.apply(this, args), ms);
  };
}
