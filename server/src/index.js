/**
 * Claude Glasses Terminal Server
 *
 * WebSocket server that:
 * 1. Runs Claude Code in a tmux session
 * 2. Forwards terminal output to connected clients
 * 3. Receives input/commands from clients and sends to Claude Code
 * 4. Handles image uploads for Claude's vision capabilities
 */

import { WebSocketServer } from 'ws';
import stripAnsi from 'strip-ansi';
import { writeFileSync, unlinkSync, mkdtempSync } from 'fs';

/**
 * Parse ANSI color codes and return line color type
 * Returns: 'addition' (green), 'deletion' (red), 'header' (cyan), or null
 *
 * Supports:
 * - Basic colors: \x1b[31m (red), \x1b[32m (green), \x1b[36m (cyan)
 * - True color: \x1b[38;2;R;G;Bm (foreground RGB)
 */
function getLineColorType(line) {
  // Check for true color BACKGROUND: \x1b[48;2;R;G;Bm (used for diff highlighting)
  const bgColorMatch = line.match(/\x1b\[48;2;(\d+);(\d+);(\d+)m/);
  if (bgColorMatch) {
    const r = parseInt(bgColorMatch[1], 10);
    const g = parseInt(bgColorMatch[2], 10);
    const b = parseInt(bgColorMatch[3], 10);

    // Green background (additions)
    if (g > r + 30 && g > b) {
      return 'addition';
    }
    // Red background (deletions)
    if (r > g + 30 && r > b) {
      return 'deletion';
    }
  }

  // Check for true color FOREGROUND: \x1b[38;2;R;G;Bm
  const fgColorMatch = line.match(/\x1b\[38;2;(\d+);(\d+);(\d+)m/);
  if (fgColorMatch) {
    const r = parseInt(fgColorMatch[1], 10);
    const g = parseInt(fgColorMatch[2], 10);
    const b = parseInt(fgColorMatch[3], 10);

    // Green-ish (additions): high green, low red
    if (g > 150 && r < 100 && b < 150) {
      return 'addition';
    }
    // Red-ish (deletions): high red, low green
    if (r > 150 && g < 100) {
      return 'deletion';
    }
    // Cyan-ish (headers): high green and blue, low red
    if (g > 150 && b > 150 && r < 100) {
      return 'header';
    }
  }

  // Check for basic ANSI color codes: \x1b[XXm
  const basicMatch = line.match(/\x1b\[(\d+)m/);
  if (basicMatch) {
    const code = parseInt(basicMatch[1], 10);
    switch (code) {
      case 31: case 91: return 'deletion';   // Red / Bright red
      case 32: case 92: return 'addition';   // Green / Bright green
      case 36: case 96: return 'header';     // Cyan / Bright cyan
      case 41: case 101: return 'deletion';  // Red background
      case 42: case 102: return 'addition';  // Green background
      default: break;
    }
  }

  return null;
}
import { tmpdir } from 'os';
import { join } from 'path';
import { spawn, execSync } from 'child_process';

const PORT = process.env.PORT || 8080;
const CLAUDE_COMMAND = process.env.CLAUDE_COMMAND || 'claude';
const DEFAULT_SESSION_NAME = 'claude-glasses';

// Terminal configuration optimized for glasses HUD display
// Rokid glasses display is 480x640 in portrait orientation
const DEFAULT_COLS = 64;  // Portrait mode width
const DEFAULT_ROWS = 31;  // More rows for portrait height

class ClaudeTerminalServer {
  constructor() {
    this.wss = null;
    this.clients = new Set();
    this.outputBuffer = [];
    this.maxBufferLines = 500;
    this.tempDir = mkdtempSync(join(tmpdir(), 'claude-glasses-'));
    this.pollInterval = null;
    this.lastOutput = '';
    // Last sent to clients (for delta computation)
    this.lastSentLines = [];
    this.lastSentColors = [];
    this.cols = DEFAULT_COLS;
    this.rows = DEFAULT_ROWS;
    this.sessionName = DEFAULT_SESSION_NAME;
    // Throttle: pending latest state to send
    this.pendingLines = null;
    this.pendingColors = null;
    this.broadcastTimer = null;
    this.MIN_BROADCAST_INTERVAL = 50; // ms between broadcasts
  }

  start() {
    // Create WebSocket server
    this.wss = new WebSocketServer({ port: PORT });
    console.log(`Claude Glasses Terminal Server listening on port ${PORT}`);

    this.wss.on('connection', (ws) => {
      console.log('Client connected');
      this.clients.add(ws);

      // Send terminal info and current buffer to new client
      this.sendToClient(ws, {
        type: 'terminal_info',
        cols: this.cols,
        rows: this.rows
      });

      this.sendToClient(ws, {
        type: 'terminal_update',
        lines: this.outputBuffer,
        totalLines: this.outputBuffer.length
      });

      ws.on('message', (data) => {
        this.handleClientMessage(ws, data.toString());
      });

      ws.on('close', () => {
        console.log('Client disconnected');
        this.clients.delete(ws);

        // Release session size constraint when no clients are connected
        if (this.clients.size === 0) {
          this.releaseSessionSize(this.sessionName);
        }
      });

      ws.on('error', (err) => {
        console.error('WebSocket error:', err);
        this.clients.delete(ws);

        // Release session size constraint when no clients are connected
        if (this.clients.size === 0) {
          this.releaseSessionSize(this.sessionName);
        }
      });
    });

    // Setup tmux session
    this.setupTmux();
  }

  setupTmux() {
    // Check if session already exists
    const sessionExists = this.sessionExists(this.sessionName);

    if (sessionExists) {
      console.log(`Connecting to existing tmux session '${this.sessionName}'...`);
      // Force the terminal size for existing session
      try {
        execSync(`tmux set-option -t ${this.sessionName} window-size manual`);
        execSync(`tmux resize-window -t ${this.sessionName} -x ${this.cols} -y ${this.rows}`);
        console.log(`Resized existing session to ${this.cols}x${this.rows}`);
      } catch (e) {
        console.warn('Could not resize existing session:', e.message);
      }
    } else {
      // Create new tmux session with Claude Code
      console.log(`Creating tmux session '${this.sessionName}' (${this.cols}x${this.rows}) with Claude Code...`);
      try {
        // Create detached session
        execSync(`tmux new-session -d -s ${this.sessionName} "${CLAUDE_COMMAND}"`);

        // Force the terminal size - tmux detached sessions need explicit sizing
        execSync(`tmux set-option -t ${this.sessionName} window-size manual`);
        execSync(`tmux resize-window -t ${this.sessionName} -x ${this.cols} -y ${this.rows}`);

        console.log('tmux session created successfully');
      } catch (e) {
        console.error('Failed to create tmux session:', e.message);
        console.log('Make sure tmux is installed: brew install tmux');
        return;
      }
    }

    // Start polling for output
    this.startOutputPolling();
  }

  sessionExists(name) {
    try {
      execSync(`tmux has-session -t ${name} 2>/dev/null`);
      return true;
    } catch (e) {
      return false;
    }
  }

  /**
   * Release the forced window size on a session, allowing it to resize naturally
   * when used from a regular terminal
   */
  releaseSessionSize(name) {
    if (!this.sessionExists(name)) return;
    try {
      execSync(`tmux set-option -t ${name} -u window-size`);
      console.log(`Released size constraint on session '${name}'`);
    } catch (e) {
      console.warn(`Could not release size on session '${name}':`, e.message);
    }
  }

  listSessions() {
    try {
      const output = execSync('tmux list-sessions -F "#{session_name}"', { encoding: 'utf8' });
      return output.trim().split('\n').filter(s => s.length > 0);
    } catch (e) {
      // No tmux server running
      return [];
    }
  }

  switchSession(name) {
    // Stop current polling
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
      this.pollInterval = null;
    }

    // Release size constraint on the old session
    const oldSession = this.sessionName;
    if (oldSession !== name) {
      this.releaseSessionSize(oldSession);
    }

    this.sessionName = name;
    this.lastOutput = '';  // Force refresh
    this.outputBuffer = [];
    this.lastSentLines = [];
    this.lastSentColors = [];

    if (!this.sessionExists(name)) {
      // Create new session
      console.log(`Creating new tmux session '${name}'...`);
      try {
        execSync(`tmux new-session -d -s ${name} "${CLAUDE_COMMAND}"`);
        execSync(`tmux set-option -t ${name} window-size manual`);
        execSync(`tmux resize-window -t ${name} -x ${this.cols} -y ${this.rows}`);
      } catch (e) {
        console.error('Failed to create session:', e.message);
        return false;
      }
    } else {
      // Force resize existing session
      try {
        execSync(`tmux set-option -t ${name} window-size manual`);
        execSync(`tmux resize-window -t ${name} -x ${this.cols} -y ${this.rows}`);
      } catch (e) {
        console.warn('Could not resize session:', e.message);
      }
    }

    console.log(`Switched to session '${name}' (${this.cols}x${this.rows})`);
    this.startOutputPolling();
    return true;
  }

  killSession(name) {
    // Cannot kill current session
    if (name === this.sessionName) {
      console.warn(`Cannot kill current session '${name}'`);
      return false;
    }

    if (!this.sessionExists(name)) {
      console.warn(`Session '${name}' does not exist`);
      return false;
    }

    try {
      execSync(`tmux kill-session -t ${name}`);
      console.log(`Killed session '${name}'`);
      return true;
    } catch (e) {
      console.error(`Failed to kill session '${name}':`, e.message);
      return false;
    }
  }

  startOutputPolling() {
    // Poll tmux for output every 200ms
    if (this.pollInterval) clearInterval(this.pollInterval);
    this.pollInterval = setInterval(() => {
      this.captureOutput();
    }, 200);
  }

  captureOutput() {
    try {
      // Capture the entire visible pane content with ANSI escape sequences (-e)
      const output = execSync(
        `tmux capture-pane -t ${this.sessionName} -p -e -S -${this.maxBufferLines}`,
        { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 }
      );

      // Quick check: skip if raw output is identical
      if (output === this.lastOutput) return;
      this.lastOutput = output;

      // Split into raw lines (with ANSI) for color detection
      const rawLines = output.split('\n');

      // Extract color type for each line BEFORE stripping ANSI
      const lineColors = rawLines.map(line => getLineColorType(line));

      // Strip ANSI codes for display text
      const cleanedOutput = stripAnsi(output);
      const lines = cleanedOutput.split('\n');

      // Remove trailing empty lines but keep structure
      let trimmedLines = [...lines];
      let trimmedColors = [...lineColors];
      while (trimmedLines.length > 0 && trimmedLines[trimmedLines.length - 1].trim() === '') {
        trimmedLines.pop();
        trimmedColors.pop();
      }

      // Update buffer
      this.outputBuffer = lines;

      // Store latest state for throttled send
      this.pendingLines = trimmedLines;
      this.pendingColors = trimmedColors;

      // Throttle: schedule a send if not already scheduled
      if (!this.broadcastTimer) {
        this.broadcastTimer = setTimeout(() => {
          this.broadcastTimer = null;
          this.flushPending();
        }, this.MIN_BROADCAST_INTERVAL);
      }
    } catch (e) {
      // Session might have ended
      if (e.message.includes('no server running') || e.message.includes("can't find")) {
        console.log('tmux session ended, restarting...');
        this.setupTmux();
      }
    }
  }

  /**
   * Flush pending state: compute delta against last sent, then broadcast.
   */
  flushPending() {
    const lines = this.pendingLines;
    const colors = this.pendingColors;
    if (!lines) return;
    this.pendingLines = null;
    this.pendingColors = null;

    // Compute delta against last SENT state
    const changedIndices = [];
    const maxLen = Math.max(lines.length, this.lastSentLines.length);
    for (let i = 0; i < maxLen; i++) {
      if (lines[i] !== this.lastSentLines[i] || colors[i] !== this.lastSentColors[i]) {
        changedIndices.push(i);
      }
    }

    // Nothing changed since last send
    if (changedIndices.length === 0) return;

    // Update last sent state
    this.lastSentLines = lines;
    this.lastSentColors = colors;

    // If more than half the lines changed, send full update
    if (changedIndices.length > lines.length * 0.5) {
      this.broadcast({
        type: 'output',
        lines: lines,
        lineColors: colors,
        totalLines: lines.length
      });
    } else {
      // Send delta: only the changed lines
      const changedLines = {};
      const changedColors = {};
      for (const i of changedIndices) {
        changedLines[i] = lines[i] || '';
        changedColors[i] = colors[i] || null;
      }
      this.broadcast({
        type: 'output_delta',
        changedLines,
        changedColors,
        totalLines: lines.length
      });
    }
  }

  handleClientMessage(ws, message) {
    try {
      const msg = JSON.parse(message);
      console.log('Received:', msg.type, msg.text || msg.key || '');

      switch (msg.type) {
        case 'input':
          // Send text input to tmux
          this.sendToTmux(msg.text);
          break;

        case 'key':
          // Send special key press
          this.handleSpecialKey(msg.key);
          break;

        case 'image':
          // Image from glasses camera
          this.handleImage(msg.data, ws);
          break;

        case 'resize':
          // Terminal resize from client
          if (msg.cols && msg.rows) {
            this.resizeTerminal(msg.cols, msg.rows);
          }
          break;

        case 'list_sessions':
          // List available tmux sessions
          const sessions = this.listSessions();
          this.sendToClient(ws, {
            type: 'sessions',
            sessions: sessions,
            current: this.sessionName
          });
          break;

        case 'switch_session':
          // Switch to a different session (or create new)
          if (msg.session) {
            const success = this.switchSession(msg.session);
            this.sendToClient(ws, {
              type: 'session_switched',
              session: msg.session,
              success: success
            });
          }
          break;

        case 'kill_session':
          // Kill a tmux session
          if (msg.session) {
            const success = this.killSession(msg.session);
            this.sendToClient(ws, {
              type: 'session_killed',
              session: msg.session,
              success: success
            });
          }
          break;

        default:
          console.warn('Unknown message type:', msg.type);
      }
    } catch (err) {
      console.error('Error handling message:', err);
      this.sendToClient(ws, {
        type: 'error',
        error: err.message
      });
    }
  }

  resizeTerminal(cols, rows) {
    console.log(`Resizing terminal to ${cols}x${rows}`);
    this.cols = cols;
    this.rows = rows;
    try {
      // Resize the tmux pane
      execSync(`tmux resize-pane -t ${this.sessionName} -x ${cols} -y ${rows}`);
      // Force output capture after resize
      this.lastOutput = '';  // Force refresh
      setTimeout(() => this.captureOutput(), 100);
    } catch (e) {
      console.error('Error resizing terminal:', e.message);
    }
  }

  sendToTmux(text) {
    try {
      console.log(`Sending to tmux (${text.length} chars): "${text.substring(0, 50)}${text.length > 50 ? '...' : ''}"`);

      // For long text, use tmux buffer to avoid command line length limits
      if (text.length > 1000) {
        // Write to temp file, load into tmux buffer, paste
        const tempFile = join(this.tempDir, `input-${Date.now()}.txt`);
        writeFileSync(tempFile, text);
        execSync(`tmux load-buffer -b claude-input "${tempFile}"`);
        execSync(`tmux paste-buffer -b claude-input -t ${this.sessionName}`);
        unlinkSync(tempFile);
      } else {
        // For short text, use send-keys with -l (literal) flag
        // Escape single quotes for shell
        const escaped = text.replace(/'/g, "'\\''");
        execSync(`tmux send-keys -t ${this.sessionName} -l '${escaped}'`);
      }
      // Force a capture after sending
      setTimeout(() => this.captureOutput(), 50);
    } catch (e) {
      console.error('Error sending to tmux:', e.message);
    }
  }

  handleSpecialKey(key) {
    const keyMap = {
      'escape': 'Escape',
      'enter': 'Enter',
      'tab': 'Tab',
      'shift_tab': 'BTab',
      'up': 'Up',
      'down': 'Down',
      'left': 'Left',
      'right': 'Right',
      'backspace': 'BSpace',
      'ctrl_b': 'C-b',
      'ctrl_c': 'C-c',
      'ctrl_d': 'C-d',
      'ctrl_o': 'C-o',
      'page_up': 'PageUp',
      'page_down': 'PageDown',
      'slash': '/',
      'backslash': '\\'
    };


    if (key === 'ctrl_u') {
      try {
        execSync(`tmux send-keys -t ${this.sessionName} C-u`);
        execSync(`tmux send-keys -t ${this.sessionName} C-k`);
        execSync(`tmux send-keys -t ${this.sessionName} Delete`);
        execSync(`tmux send-keys -t ${this.sessionName} BSpace`);
      } catch (e) {
        console.error('Error sending clear to tmux:', e.message);
      }
      return;
    }

    const tmuxKey = keyMap[key];
    if (tmuxKey) {
      try {
        execSync(`tmux send-keys -t ${this.sessionName} ${tmuxKey}`);
      } catch (e) {
        console.error('Error sending key to tmux:', e.message);
      }
    } else {
      console.warn('Unknown key:', key);
    }
  }

  handleImage(base64Data, ws) {
    try {
      // Save image to temp file
      const imagePath = join(this.tempDir, `screenshot-${Date.now()}.png`);
      const imageBuffer = Buffer.from(base64Data, 'base64');
      writeFileSync(imagePath, imageBuffer);

      console.log(`Saved screenshot: ${imagePath}`);

      // For Claude Code, we could potentially use the image path
      // This would require Claude Code to support image input in the current context

      this.sendToClient(ws, {
        type: 'image_received',
        path: imagePath
      });

      // Clean up old screenshots after 5 minutes
      setTimeout(() => {
        try {
          unlinkSync(imagePath);
        } catch (e) {
          // Ignore if already deleted
        }
      }, 5 * 60 * 1000);

    } catch (err) {
      console.error('Error handling image:', err);
      this.sendToClient(ws, {
        type: 'error',
        error: 'Failed to process image'
      });
    }
  }

  sendToClient(ws, message) {
    if (ws.readyState === ws.OPEN) {
      ws.send(JSON.stringify(message));
    }
  }

  broadcast(message) {
    const json = JSON.stringify(message);
    for (const client of this.clients) {
      if (client.readyState === client.OPEN) {
        client.send(json);
      }
    }
  }

  stop() {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
    }

    // Don't kill tmux session - let it persist for reconnection

    if (this.wss) {
      this.wss.close();
    }
  }
}

// Start server
const server = new ClaudeTerminalServer();
server.start();

// Handle graceful shutdown
process.on('SIGINT', () => {
  console.log('\nShutting down...');
  server.stop();
  process.exit(0);
});

process.on('SIGTERM', () => {
  console.log('Received SIGTERM, shutting down...');
  server.stop();
  process.exit(0);
});
