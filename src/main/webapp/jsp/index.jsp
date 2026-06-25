<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AIS Assistant</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: 'Segoe UI', sans-serif;
            background: #1a1a2e;
            color: #eee;
            height: 100vh;
            display: flex;
            flex-direction: column;
        }
        header {
            background: #16213e;
            padding: 1rem 2rem;
            border-bottom: 2px solid #0f3460;
            display: flex;
            align-items: center;
            gap: 1rem;
        }
        header h1 { font-size: 1.4rem; color: #e94560; }
        header span { color: #888; font-size: 0.9rem; }

        /* ── Quick Prompt Buttons ─────────────────────────────────── */
        #quick-prompts {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            padding: 12px 1rem;
            background: #1a1a2e;
            border-bottom: 1px solid #0f3460;
            min-height: 50px;
        }

        #quick-prompts.loading {
            justify-content: center;
            align-items: center;
            color: #666;
            font-style: italic;
        }

        .quick-btn {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 8px 14px;
            border-radius: 20px;
            border: 1px solid #0f3460;
            background: #16213e;
            color: #ccc;
            font-size: 0.82rem;
            cursor: pointer;
            transition: all 0.2s;
            white-space: nowrap;
            user-select: none;
        }

        .quick-btn:hover {
            background: #0f3460;
            color: #fff;
            border-color: #e94560;
            transform: translateY(-1px);
        }

        .quick-btn:active {
            transform: translateY(0);
        }

        /* Auto-send buttons (no input needed) */
        .quick-btn.auto-send {
            border-color: #2e7d32;
        }
        .quick-btn.auto-send:hover {
            background: #1b5e20;
            border-color: #4caf50;
        }

        /* Fill-only buttons (needs input) */
        .quick-btn.fill-only {
            border-color: #1565c0;
        }
        .quick-btn.fill-only:hover {
            background: #0d47a1;
            border-color: #42a5f5;
        }

        .quick-btn .qicon {
            font-size: 1rem;
        }

        .quick-btn .needs-input {
            display: inline-block;
            background: rgba(233,69,96,0.3);
            color: #e94560;
            font-size: 0.7rem;
            padding: 1px 5px;
            border-radius: 3px;
            margin-left: 2px;
        }

        #chat-container {
            flex: 1;
            overflow-y: auto;
            padding: 1.5rem;
            display: flex;
            flex-direction: column;
            gap: 1rem;
        }
        .message {
            max-width: 75%;
            padding: 0.8rem 1.2rem;
            border-radius: 12px;
            line-height: 1.5;
            font-size: 0.95rem;
        }
        .message.user {
            background: #0f3460;
            align-self: flex-end;
            border-bottom-right-radius: 4px;
        }
        .message.assistant {
            background: #16213e;
            align-self: flex-start;
            border-bottom-left-radius: 4px;
            border: 1px solid #0f3460;
        }
        .message.tool-info {
            background: #0d2137;
            align-self: flex-start;
            border-left: 3px solid #e94560;
            font-size: 0.82rem;
            font-family: monospace;
            max-width: 90%;
        }
        .tool-badge {
            display: inline-block;
            background: #e94560;
            color: white;
            border-radius: 4px;
            padding: 1px 6px;
            font-size: 0.75rem;
            margin-bottom: 4px;
        }
        .timing {
            font-size: 0.75rem;
            color: #666;
            align-self: flex-end;
        }
        #input-area {
            display: flex;
            padding: 1rem;
            gap: 0.5rem;
            background: #16213e;
            border-top: 1px solid #0f3460;
        }
        #prompt-input {
            flex: 1;
            padding: 0.75rem 1rem;
            border-radius: 8px;
            border: 1px solid #0f3460;
            background: #1a1a2e;
            color: #eee;
            font-size: 1rem;
            outline: none;
            transition: border-color 0.2s, box-shadow 0.2s;
        }
        #prompt-input:focus { border-color: #e94560; }

        #prompt-input.prefilled {
            border-color: #42a5f5;
            box-shadow: 0 0 8px rgba(66, 165, 245, 0.3);
        }

        #send-btn {
            padding: 0.75rem 1.5rem;
            background: #e94560;
            color: white;
            border: none;
            border-radius: 8px;
            cursor: pointer;
            font-size: 1rem;
            transition: background 0.2s;
        }
        #send-btn:hover    { background: #c73652; }
        #send-btn:disabled { background: #555; cursor: not-allowed; }
        
        #loading {
            display: none;
            align-self: flex-start;
            color: #888;
            font-style: italic;
            font-size: 0.9rem;
        }
        pre {
            white-space: pre-wrap;
            word-break: break-word;
        }

        /* ── Data table styling ─────────────────────────────────── */
        .message.assistant .data-title {
            color: #e94560;
            margin-bottom: 12px;
            padding-bottom: 8px;
            border-bottom: 2px solid #0f3460;
            font-size: 1.1rem;
        }
        .message.assistant .data-code {
            color: #888;
            font-size: 0.85rem;
            font-weight: normal;
        }
        .message.assistant .data-table {
            width: 100%;
            border-collapse: collapse;
            margin-top: 8px;
            font-size: 0.88rem;
        }
        .message.assistant .data-table th {
            background: #0f3460;
            color: #e94560;
            text-align: left;
            padding: 8px 12px;
            width: 40%;
            font-weight: 500;
        }
        .message.assistant .data-table td {
            padding: 8px 12px;
            border-top: 1px solid #1a3d6e;
            color: #ddd;
        }
        .message.assistant .data-table tr td,
        .message.assistant .data-table tr th {
            background: #0d2137;
        }
        .message.assistant .data-footer {
            margin-top: 10px;
            font-size: 0.75rem;
            color: #888;
            font-style: italic;
        }
        .message.assistant .answer-summary {
            margin-bottom: 12px;
            color: #fff;
            font-size: 0.95rem;
            line-height: 1.5;
        }
        .message.assistant .error-box {
            background: #5a1a2a;
            padding: 10px;
            border-radius: 6px;
            color: #ff6b6b;
        }
        .message.assistant code {
            background: #0f3460;
            padding: 2px 6px;
            border-radius: 3px;
            font-family: 'Courier New', monospace;
            font-size: 0.85rem;
            color: #e94560;
        }

        /* ── Reports Grid ─────────────────────────────────────────── */
        .message.assistant .reports-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
            gap: 10px;
            margin-top: 10px;
        }
        .message.assistant .report-card {
            background: #0d2137;
            border: 1px solid #1a3d6e;
            border-radius: 8px;
            padding: 12px;
            transition: all 0.2s;
        }
        .message.assistant .report-card.report-available {
            border-color: #4caf50;
        }
        .message.assistant .report-card.report-available:hover {
            background: #143352;
            border-color: #6fbf73;
            transform: translateY(-2px);
        }
        .message.assistant .report-card.report-unavailable {
            opacity: 0.5;
        }
        .message.assistant .report-name {
            font-weight: bold;
            color: #fff;
            margin-bottom: 8px;
            font-size: 0.95rem;
        }
        .message.assistant .report-link {
            display: inline-block;
            background: #4caf50;
            color: white !important;
            padding: 6px 12px;
            border-radius: 4px;
            text-decoration: none;
            font-size: 0.85rem;
            margin-top: 4px;
            transition: background 0.2s;
        }
        .message.assistant .report-link:hover {
            background: #45a049;
        }
        .message.assistant .report-unavailable-text {
            color: #888;
            font-style: italic;
            font-size: 0.85rem;
        }
        .message.assistant .report-details {
            margin-top: 6px;
            display: flex;
            flex-direction: column;
            gap: 2px;
        }
        .message.assistant .report-details small {
            color: #aaa;
            font-size: 0.75rem;
        }
        .report-type-badge {
            display: inline-block;
            background: #e3f2fd;
            color: #1565c0;
            font-size: 11px;
            font-weight: bold;
            padding: 1px 6px;
            border-radius: 4px;
            margin-left: 6px;
            vertical-align: middle;
        }
        .report-link-unavailable {
            display: block;
            color: #e65100;
            font-size: 12px;
            margin-top: 6px;
            font-style: italic;
        }

        /* Collapsible tool call */
        .tool-details {
            border: 1px solid #ddd;
            border-radius: 6px;
            background: #f8f9fa;
            margin: 4px 0;
            overflow: hidden;
        }
        .tool-summary {
            color: #888;
            padding: 8px 12px;
            cursor: pointer;
            background: #e9ecef;
            user-select: none;
            list-style: none;
            display: flex;
            align-items: center;
            gap: 8px;
            transition: background 0.15s;
        }
        .tool-summary::-webkit-details-marker {
            display: none;
        }
        .tool-summary:hover {
            background: #dee2e6;
        }
        .tool-summary::before {
            content: "▶";
            color: #666;
            font-size: 10px;
            transition: transform 0.2s;
            display: inline-block;
        }
        .tool-details[open] .tool-summary::before {
            transform: rotate(90deg);
        }
        .tool-hint {
            color: #888;
            font-size: 11px;
            margin-left: auto;
            font-weight: normal;
            font-style: italic;
        }
        .tool-details[open] .tool-hint {
            display: none;
        }
        .tool-body {
            padding: 12px;
            background: #0d2137;
        }
        .tool-section {
            margin-bottom: 10px;
        }
        .tool-section:last-child {
            margin-bottom: 0;
        }
        .tool-section pre {
            background: #1e293b;
            color: #e2e8f0;
            padding: 10px;
            border-radius: 4px;
            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
            font-size: 12px;
            line-height: 1.4;
            overflow-x: auto;
            margin: 4px 0 0 0;
            max-height: 400px;
            overflow-y: auto;
            white-space: pre-wrap;
            word-break: break-word;
        }

        /* Collapsible PSM locations */
        .psm-locations-details {
            border: 1px solid #ddd;
            border-radius: 6px;
            background: #f8f9fa;
            margin: 10px 0;
            overflow: hidden;
        }
        .psm-locations-summary {
            padding: 10px 14px;
            cursor: pointer;
            background: #e9ecef;
            user-select: none;
            list-style: none;
            font-weight: bold;
            display: flex;
            align-items: center;
            gap: 8px;
            transition: background 0.15s;
            color: #888;
        }
        .psm-locations-summary::-webkit-details-marker {
            display: none;
        }
        .psm-locations-summary:hover {
            background: #dee2e6;
        }
        .psm-locations-summary::before {
            content: "▶";
            color: #666;
            font-size: 10px;
            transition: transform 0.2s;
        }
        .psm-locations-details[open] .psm-locations-summary::before {
            transform: rotate(90deg);
        }
        .psm-hint {
            color: #888;
            font-size: 11px;
            margin-left: auto;
            font-weight: normal;
            font-style: italic;
        }
        .psm-locations-details[open] .psm-hint {
            display: none;
        }
        .psm-locations-body {
            padding: 12px;
            background: #0d2137;
        }

        /* Tooltip for tool descriptions */
        .quick-btn[title] {
            position: relative;
        }
        
        /* ── Agent Reasoning Chain ─────────────────────────────────── */
		.agent-reasoning-chain {
		    background: #f0f4ff;
		    border: 1px solid #c7d4f0;
		    border-radius: 8px;
		    padding: 12px 16px;
		    margin-bottom: 16px;
		    font-size: 0.9em;
		}
		
		.reasoning-summary {
		    cursor: pointer;
		    color: #3a5aba;
		    font-weight: 500;
		    list-style: none;       /* hide default triangle */
		    display: flex;
		    align-items: center;
		    gap: 6px;
		}
		
		.reasoning-summary::-webkit-details-marker { display: none; }
		
		.reasoning-steps {
		    margin-top: 12px;
		    padding-left: 8px;
		}
		
		.reasoning-step {
		    display: flex;
		    flex-direction: column;
		    align-items: flex-start;
		    gap: 4px;
		}
		
		.step-number {
		    background: #3a5aba;
		    color: white;
		    border-radius: 50%;
		    width: 24px;
		    height: 24px;
		    display: flex;
		    align-items: center;
		    justify-content: center;
		    font-size: 0.85em;
		    font-weight: bold;
		    flex-shrink: 0;
		}
		
		.step-text {
		    background: white;
		    border: 1px solid #dde4f5;
		    border-radius: 6px;
		    padding: 6px 12px;
		    color: #333;
		    margin-left: 4px;
		    width: 100%;
		    box-sizing: border-box;
		}
		
		.step-connector {
		    color: #3a5aba;
		    font-size: 1.2em;
		    margin-left: 8px;
		    opacity: 0.5;
		}
		
		/* ── Single-step subtle note ──────────────────────────────── */
		.agent-reasoning-single {
		    background: #fafbff;
		    border-left: 3px solid #3a5aba;
		    padding: 6px 12px;
		    margin-bottom: 12px;
		    font-size: 0.88em;
		    color: #555;
		    border-radius: 0 4px 4px 0;
		}
		
		.reasoning-icon {
		    margin-right: 4px;
		}
    </style>
</head>
<body>
<header>
    <h1>🤖 AIS Assistant</h1>
    <span>Powered by Qwen3 + MCP Tools</span>
</header>

<!-- ── Dynamic Quick Prompt Buttons ──────────────────────────────── -->
<div id="quick-prompts" class="loading">
    ⏳ Loading tools...
</div>

<div id="chat-container">
    <div class="message assistant">
        👋 Hello! Ask me about any location code (e.g., <strong>SB04400361000</strong>)
        <br><br>
        <span style="color:#888; font-size:0.85rem;">
            💡 Tip: Use the quick buttons above to get started fast!
        </span>
    </div>
</div>

<div id="loading">⏳ Thinking...</div>

<div id="input-area">
    <input type="text"
           id="prompt-input"
           placeholder="Enter location code or question..."
           autocomplete="off"/>
    <button id="send-btn">Send ➤</button>
</div>

<script>
    const chatContainer = document.getElementById('chat-container');
    const input         = document.getElementById('prompt-input');
    const sendBtn       = document.getElementById('send-btn');
    const loading       = document.getElementById('loading');
    const quickPrompts  = document.getElementById('quick-prompts');

    const ctx = '<%= request.getContextPath() %>';

    // ── Load tools and render buttons on page load ───────────────
    async function loadTools() {
        try {
            const resp = await fetch(ctx + '/api/tools');
            if (!resp.ok) throw new Error('Failed to load tools');

            const tools = await resp.json();
            renderQuickButtons(tools);

        } catch (err) {
            console.error('Error loading tools:', err);
            quickPrompts.innerHTML = '<span style="color:#e94560;">⚠️ Failed to load quick prompts</span>';
            quickPrompts.classList.remove('loading');
        }
    }

    // ── Render quick prompt buttons from tool definitions ─────────
    function renderQuickButtons(tools) {
        quickPrompts.innerHTML = '';
        quickPrompts.classList.remove('loading');

        tools.forEach(tool => {
            const btn = document.createElement('button');
            btn.className = 'quick-btn ' + (tool.needsInput ? 'fill-only' : 'auto-send');
            btn.setAttribute('data-prompt', tool.samplePrompt || tool.name);
            btn.setAttribute('data-mode', tool.needsInput ? 'fill' : 'send');
            btn.setAttribute('data-placeholder', tool.placeholder || '');
            btn.title = tool.description || '';

            // Build button content
            let html = '<span class="qicon">' + (tool.icon || '🔧') + '</span> ';
            
            // Clean up the display name
            let displayName = tool.samplePrompt || tool.name;
            if (displayName.endsWith(' ')) {
                displayName = displayName.trim() + '…';
            }
            html += escapeHtml(displayName);

            // Add input hint badge if needed
            if (tool.needsInput && tool.inputHint) {
                html += ' <span class="needs-input">+ ' + escapeHtml(tool.inputHint) + '</span>';
            }

            btn.innerHTML = html;
            quickPrompts.appendChild(btn);
        });
    }

    // ── Quick Prompt Button Handler ─────────────────────────────
    quickPrompts.addEventListener('click', (e) => {
        const btn = e.target.closest('.quick-btn');
        if (!btn) return;

        const prompt      = btn.getAttribute('data-prompt');
        const mode        = btn.getAttribute('data-mode');
        const placeholder = btn.getAttribute('data-placeholder');

        if (mode === 'send') {
            // AUTO-SEND: Fill and immediately send
            input.value = prompt;
            input.classList.remove('prefilled');
            sendMessage();

        } else if (mode === 'fill') {
            // FILL-ONLY: Pre-fill the input, focus, let user complete
            input.value = prompt;
            input.focus();
            input.classList.add('prefilled');

            // Update placeholder to hint what to type
            if (placeholder) {
                input.setAttribute('placeholder', placeholder);
            }

            // Place cursor at end
            const len = input.value.length;
            input.setSelectionRange(len, len);
        }
    });

    function appendMessage(cls, html) {
        const div = document.createElement('div');
        div.className = 'message ' + cls;
        div.innerHTML = html;
        chatContainer.appendChild(div);
        chatContainer.scrollTop = chatContainer.scrollHeight;
        return div;
    }

    function appendTiming(ms) {
        const div = document.createElement('div');
        div.className = 'timing';
        div.textContent = '⏱ ' + (ms / 1000).toFixed(2) + 's';
        chatContainer.appendChild(div);
    }

    async function sendMessage() {
        const prompt = input.value.trim();
        if (!prompt) return;

        // Reset input state
        input.classList.remove('prefilled');
        input.setAttribute('placeholder', 'Enter location code or question...');

        // Show user message
        appendMessage('user', escapeHtml(prompt));
        input.value = '';
        sendBtn.disabled = true;
        loading.style.display = 'block';
        chatContainer.appendChild(loading);

        try {
            const resp = await fetch(ctx + '/api/chat', {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body:    JSON.stringify({ prompt })
            });
            const data = await resp.json();

            // Show tool calls
            if (data.toolCalls && data.toolCalls.length > 0) {
                for (const tc of data.toolCalls) {
                    appendMessage('tool-info', renderToolCall(tc));
                }
            }

            // Show AI answer
            if (data.error) {
                appendMessage('assistant', '❌ Error: ' + escapeHtml(data.error));
            } else {
                appendMessage('assistant', data.answer);
                appendTiming(data.elapsedMs);
            }

        } catch (err) {
            appendMessage('assistant', '❌ Network error: ' + err.message);
        } finally {
            sendBtn.disabled = false;
            loading.style.display = 'none';
        }
    }

    // Send on button click
    sendBtn.addEventListener('click', sendMessage);

    // Send on Enter key
    input.addEventListener('keydown', e => {
        if (e.key === 'Enter' && !e.shiftKey) sendMessage();
    });

    // Remove prefilled highlight when user edits
    input.addEventListener('input', () => {
        input.classList.remove('prefilled');
    });

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function renderToolCall(tc) {
        let prettyArgs = tc.args;
        try {
            const parsed = typeof tc.args === 'string'
                ? JSON.parse(tc.args) : tc.args;
            prettyArgs = JSON.stringify(parsed, null, 2);
        } catch (e) {}

        let prettyResult = tc.result;
        try {
            const parsed = JSON.parse(tc.result);
            prettyResult = JSON.stringify(parsed, null, 2);
        } catch (e) {}

        return `
            <details class="tool-details">
                <summary class="tool-summary">
                    <span class="tool-badge">🔧 TOOL</span>
                    <strong>\${escapeHtml(tc.name)}</strong>
                    <span class="tool-hint">click to expand</span>
                </summary>
                <div class="tool-body">
                    <div class="tool-section">
                        <b>Args:</b>
                        <pre>\${escapeHtml(prettyArgs)}</pre>
                    </div>
                    <div class="tool-section">
                        <b>Result:</b>
                        <pre>\${escapeHtml(prettyResult)}</pre>
                    </div>
                </div>
            </details>
        `;
    }

    // ── Initialize ───────────────────────────────────────────────
    loadTools();
</script>
</body>
</html>