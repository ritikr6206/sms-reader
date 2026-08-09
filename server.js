require('dotenv').config();
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const { v4: uuidv4 } = require('uuid');
const path = require('path');

// ============================================
// Configuration
// ============================================
const PORT = process.env.PORT || 3000;
const API_KEY = process.env.API_KEY || 'change-me';
const DASHBOARD_PASSWORD = process.env.DASHBOARD_PASSWORD || 'change-me';
const SESSION_SECRET = process.env.SESSION_SECRET || 'change-me';
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN;
const TELEGRAM_CHAT_ID = process.env.TELEGRAM_CHAT_ID;
const MESSAGE_EXPIRY_MINUTES = parseInt(process.env.MESSAGE_EXPIRY_MINUTES || '60', 10);

// ============================================
// Telegram Bot Setup
// ============================================
let telegramBot = null;

if (TELEGRAM_BOT_TOKEN && TELEGRAM_CHAT_ID) {
  try {
    const TelegramBot = require('node-telegram-bot-api');
    telegramBot = new TelegramBot(TELEGRAM_BOT_TOKEN, { polling: false });
    console.log('✅ Telegram bot initialized');
  } catch (err) {
    console.warn('⚠️  Telegram bot initialization failed:', err.message);
  }
} else {
  console.warn('⚠️  Telegram not configured — skipping bot setup');
}

async function sendTelegramNotification(smsData) {
  if (!telegramBot || !TELEGRAM_CHAT_ID) return;

  const time = new Date(smsData.timestamp).toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });

  const text =
    `📩 *New SMS Received*\n\n` +
    `👤 *From:* \`${escapeMarkdown(smsData.sender)}\`\n` +
    `🕐 *Time:* ${escapeMarkdown(time)}\n\n` +
    `💬 *Message:*\n${escapeMarkdown(smsData.message)}`;

  try {
    await telegramBot.sendMessage(TELEGRAM_CHAT_ID, text, {
      parse_mode: 'MarkdownV2',
    });
  } catch (err) {
    // Fallback to plain text if markdown fails
    const plainText =
      `📩 New SMS Received\n\n` +
      `From: ${smsData.sender}\n` +
      `Time: ${time}\n\n` +
      `Message:\n${smsData.message}`;
    try {
      await telegramBot.sendMessage(TELEGRAM_CHAT_ID, plainText);
    } catch (fallbackErr) {
      console.error('❌ Telegram send failed:', fallbackErr.message);
    }
  }
}

function escapeMarkdown(text) {
  return text.replace(/([_*\[\]()~`>#+\-=|{}.!\\])/g, '\\$1');
}

// ============================================
// In-Memory Message Store
// ============================================
const messages = [];
const MAX_MESSAGES = 200;

function addMessage(smsData) {
  const msg = {
    id: uuidv4(),
    sender: smsData.sender,
    message: smsData.message,
    timestamp: smsData.timestamp || Date.now(),
    receivedAt: Date.now(),
    expiresAt: Date.now() + MESSAGE_EXPIRY_MINUTES * 60 * 1000,
  };
  messages.unshift(msg); // newest first

  // Cap the list
  if (messages.length > MAX_MESSAGES) {
    messages.length = MAX_MESSAGES;
  }

  return msg;
}

function getActiveMessages() {
  const now = Date.now();
  // Remove expired messages in-place
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].expiresAt <= now) {
      messages.splice(i, 1);
    }
  }
  return messages;
}

// Cleanup expired messages every minute
setInterval(() => {
  getActiveMessages();
}, 60 * 1000);

// ============================================
// Session Store (simple token-based)
// ============================================
const sessions = new Map();
const SESSION_EXPIRY_MS = 24 * 60 * 60 * 1000; // 24 hours

function createSession() {
  const token = uuidv4();
  sessions.set(token, {
    createdAt: Date.now(),
    expiresAt: Date.now() + SESSION_EXPIRY_MS,
  });
  return token;
}

function isValidSession(token) {
  if (!token) return false;
  const session = sessions.get(token);
  if (!session) return false;
  if (Date.now() > session.expiresAt) {
    sessions.delete(token);
    return false;
  }
  return true;
}

// Cleanup expired sessions every 10 minutes
setInterval(() => {
  const now = Date.now();
  for (const [token, session] of sessions) {
    if (now > session.expiresAt) {
      sessions.delete(token);
    }
  }
}, 10 * 60 * 1000);

// ============================================
// Express App
// ============================================
const app = express();

// Security middleware
app.use(
  helmet({
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],
        scriptSrc: ["'self'", "'unsafe-inline'"],
        styleSrc: ["'self'", "'unsafe-inline'", 'https://fonts.googleapis.com'],
        fontSrc: ["'self'", 'https://fonts.gstatic.com'],
        imgSrc: ["'self'", 'data:'],
        connectSrc: ["'self'"],
      },
    },
  })
);
app.use(cors());
app.use(express.json({ limit: '1mb' }));

// Rate limiting
const apiLimiter = rateLimit({
  windowMs: 1 * 60 * 1000, // 1 minute
  max: 60,
  message: { error: 'Too many requests, slow down' },
});
app.use('/api/', apiLimiter);

// Serve static files (dashboard)
app.use(express.static(path.join(__dirname, 'public')));

// ============================================
// API Routes
// ============================================

// Middleware: Verify API Key (for Android app)
function requireApiKey(req, res, next) {
  const key = req.headers['x-api-key'];
  if (!key || key !== API_KEY) {
    return res.status(401).json({ error: 'Invalid API key' });
  }
  next();
}

// Middleware: Verify Dashboard Session
function requireSession(req, res, next) {
  const token =
    req.headers['authorization']?.replace('Bearer ', '') ||
    req.query.token;
  if (!isValidSession(token)) {
    return res.status(401).json({ error: 'Unauthorized — please login' });
  }
  next();
}

// POST /api/sms — Android app sends SMS here
app.post('/api/sms', requireApiKey, async (req, res) => {
  const { sender, message, timestamp } = req.body;

  if (!sender || !message) {
    return res.status(400).json({ error: 'Missing sender or message' });
  }

  const msg = addMessage({ sender, message, timestamp });
  console.log(`📩 SMS from ${sender}: ${message.substring(0, 50)}...`);

  // Send Telegram notification (non-blocking)
  sendTelegramNotification(msg).catch(() => {});

  res.json({ success: true, id: msg.id });
});

// POST /api/sms/batch — Android app sends queued messages in batch
app.post('/api/sms/batch', requireApiKey, async (req, res) => {
  const { messages: smsList } = req.body;

  if (!Array.isArray(smsList) || smsList.length === 0) {
    return res.status(400).json({ error: 'Missing or empty messages array' });
  }

  const results = [];
  for (const sms of smsList) {
    if (!sms.sender || !sms.message) continue;
    const msg = addMessage(sms);
    sendTelegramNotification(msg).catch(() => {});
    results.push({ id: msg.id, sender: sms.sender });
  }

  console.log(`📩 Batch: received ${results.length} queued messages`);
  res.json({ success: true, count: results.length, results });
});

// POST /api/login — Dashboard login
app.post('/api/login', (req, res) => {
  const { password } = req.body;

  if (!password || password !== DASHBOARD_PASSWORD) {
    return res.status(401).json({ error: 'Wrong password' });
  }

  const token = createSession();
  res.json({ success: true, token });
});

// GET /api/messages — Get recent messages (for dashboard)
app.get('/api/messages', requireSession, (req, res) => {
  const active = getActiveMessages();
  const search = (req.query.search || '').toLowerCase();

  let filtered = active;
  if (search) {
    filtered = active.filter(
      (m) =>
        m.sender.toLowerCase().includes(search) ||
        m.message.toLowerCase().includes(search)
    );
  }

  res.json({
    messages: filtered,
    total: active.length,
    filtered: filtered.length,
    expiryMinutes: MESSAGE_EXPIRY_MINUTES,
  });
});

// GET /api/status — Health check
app.get('/api/status', (req, res) => {
  res.json({
    status: 'online',
    uptime: Math.floor(process.uptime()),
    messageCount: messages.length,
    telegramConnected: !!telegramBot,
  });
});

// Fallback — serve dashboard for any unknown route
app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// ============================================
// Start Server
// ============================================
app.listen(PORT, () => {
  console.log(`\n🚀 SMS Reader Server running on port ${PORT}`);
  console.log(`📊 Dashboard: http://localhost:${PORT}`);
  console.log(`⏱️  Message expiry: ${MESSAGE_EXPIRY_MINUTES} minutes`);
  console.log(`🔑 API Key: ${API_KEY.substring(0, 8)}...`);
  console.log(
    `🤖 Telegram: ${telegramBot ? 'Connected' : 'Not configured'}\n`
  );
});
