<%@ page contentType="text/html;charset=UTF-8" isELIgnored="true" %>

    <!DOCTYPE html>
    <html lang="en">

    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>AIS Assistant</title>
        <link rel="stylesheet"
            href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/7.0.1/css/all.min.css">
        <style>
            * {
                box-sizing: border-box;
                margin: 0;
                padding: 0;
            }

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

            header h1 {
                font-size: 1.4rem;
                color: #e94560;
            }

            header span {
                color: #888;
                font-size: 0.9rem;
            }

            /* ── Quick Prompt / "/skill" Tools Panel ─────────────────────
           Hidden by default. Revealed only when the chat input starts
           with "/skill" (live, as the user types). See #quick-prompts.visible
           and updateSkillTrigger() in js/chat.js.
           Sits directly above #input-area (not at the page top) so it
           appears right next to where the user is typing.
           Rendered as a numbered vertical list (not pill buttons):
           click/select a row to put that tool's name into the input,
           then press Tab to expand it to the full sample prompt. ──── */
            #quick-prompts {
                display: none;
                flex-direction: column;
                gap: 2px;
                padding: 8px 1rem;
                background: #1a1a2e;
                border-top: 1px solid #0f3460;
                max-height: 260px;
                overflow-y: auto;
            }

            #quick-prompts.visible {
                display: flex;
            }

            #quick-prompts.loading,
            #quick-prompts.empty {
                justify-content: center;
                align-items: center;
                color: #666;
                font-style: italic;
                min-height: 50px;
            }

            #skill-hint {
                color: #888;
                font-size: 0.75rem;
                font-style: italic;
                padding: 4px 10px 8px;
                border-bottom: 1px solid #0f3460;
                margin-bottom: 4px;
            }

            .quick-btn {
                display: flex;
                align-items: center;
                gap: 8px;
                padding: 8px 10px;
                border-radius: 6px;
                border: 1px solid transparent;
                background: transparent;
                color: #ccc;
                font-size: 0.85rem;
                cursor: pointer;
                transition: background 0.15s, border-color 0.15s;
                user-select: none;
                width: 100%;
                text-align: left;
            }

            .quick-btn:hover,
            .quick-btn.active {
                background: #0f3460;
                color: #fff;
                border-color: #e94560;
            }

            .quick-btn:active {
                background: #0d2137;
            }

            .quick-btn .qindex {
                color: #666;
                font-size: 0.8rem;
                min-width: 1.6em;
                text-align: right;
                flex-shrink: 0;
            }

            .quick-btn.active .qindex {
                color: #ffb3bd;
            }

            /* Auto-send tools get a green accent, fill-only tools blue */
            .quick-btn.auto-send .qicon {
                color: #4caf50;
            }

            .quick-btn.fill-only .qicon {
                color: #42a5f5;
            }

            .quick-btn .qicon {
                font-size: 1rem;
                flex-shrink: 0;
            }

            .quick-btn .qname {
                font-weight: 600;
                color: #fff;
                flex-shrink: 0;
            }

            .quick-btn .qdesc {
                color: #999;
                font-size: 0.8rem;
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
            }

            .quick-btn .needs-input {
                display: inline-block;
                background: rgba(233, 69, 96, 0.3);
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

            /* ── Location detail: sticky map as a sibling, not nested ───
           formatSingleLocation() (OllamaService.java) marks its AIS
           link + map iframe with class="location-map-block", still
           inline at the end of the message HTML it returns. js/chat.js
           detects that marker, physically removes it from the message
           content, and re-parents it into its own sibling div
           (.location-map-sticky) next to (not inside) the message
           bubble — wrapped together in a .message-row flex row so
           they sit side by side. The message keeps a `.has-map`
           modifier class purely as a styling/JS hook; it no longer
           contains the map itself. ─────────────────────────────────── */
            .message-row {
                display: flex;
                align-items: stretch;
                align-self: flex-start;
                gap: 20px;
                width: 100%;
            }

            .message-row .message.assistant.has-map {
                max-width: none;
                flex: 1 1 auto;
                min-width: 0;
                /* allow the message to shrink/scroll instead of overflowing the row */
            }

            .location-map-sticky {
                flex: 0 0 70%;
                align-self: stretch;
                /* Sticks to the viewport while scrolling, but only within the
               vertical span of .message-row (its containing block) — so
               it can never appear above this message's own top, and it
               scrolls away again once the message's bottom passes. The
               12px top offset just keeps a small gutter below the page
               header instead of touching it edge-to-edge. */
                position: sticky;
                top: 0px;
                height: auto;
                max-height: 85vh;
            }

            .location-map-sticky iframe {
                display: block;
                width: 100%;
                height: 90%;
            }

            /* Narrow screens: stack instead of side-by-side (sticky columns
           don't make sense once there's no horizontal room to spare). */
            @media (max-width: 900px) {
                .message-row {
                    flex-direction: column;
                    max-width: 100%;
                }

                .location-map-sticky {
                    position: static;
                    width: 100%;
                    flex-basis: auto;
                }
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

            #prompt-input:focus {
                border-color: #e94560;
            }

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

            #send-btn:hover {
                background: #c73652;
            }

            #send-btn:disabled {
                background: #555;
                cursor: not-allowed;
            }

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
                list-style: none;
                /* hide default triangle */
                display: flex;
                align-items: center;
                gap: 6px;
            }

            .reasoning-summary::-webkit-details-marker {
                display: none;
            }

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

            .ais-detail-btn {
                display: inline-flex;
                align-items: center;
                gap: 6px;
                padding: 8px 16px;
                background-color: #1a73e8;
                color: #ffffff;
                text-decoration: none;
                border-radius: 6px;
                font-size: 14px;
                font-weight: 600;
                border: none;
                cursor: pointer;
                white-space: nowrap;
                transition: background-color 0.2s ease, box-shadow 0.2s ease;
            }

            .ais-detail-btn:hover {
                background-color: #1558b0;
                box-shadow: 0 2px 8px rgba(0, 0, 0, 0.25);
                color: #ffffff;
                text-decoration: none;
            }

            .ais-detail-btn:active {
                background-color: #0f3f7a;
            }
        </style>
    </head>

    <body>
        <header>
            <h1><i class='fa-solid fa-robot'></i> AIS Assistant</h1>
            <span>Powered by DeepSeek + MCP Tools</span>
        </header>

        <div id="chat-container">
            <div class="message assistant">
                <i class="fa-solid fa-hands"></i> Hello! Ask me about any location code (e.g., <strong>SB04400361000</strong>)
                <br><br>
                <span style="color:#888; font-size:0.85rem;">
                    <i class='fa-solid fa-lightbulb'></i> Tip: Type <code>/skill</code> in the box below to see
                    available tools, then press
                    <code>Tab</code> to autocomplete!
                </span>
            </div>
        </div>

        <div id="loading"><i class="fa-solid fa-hourglass-half"></i> Thinking...</div>

        <!-- ── Dynamic Quick Prompt / "/skill" Tools Panel ───────────────────
     Hidden until the user types /skill in the chat input (see js/chat.js).
     Positioned directly above the input box, not at the page top. -->
        <div id="quick-prompts">
            <i class="fa-solid fa-hourglass-half"></i> Loading tools...
        </div>

        <div id="input-area">
            <input type="text" id="prompt-input"
                placeholder="Enter location code or question... (type /skill for tools, Tab to autocomplete)"
                autocomplete="off" 
                autofocus/>
            <button id="send-btn">Send ➤</button>
        </div>

        <script>
            // Expose the deployment context path as a global for js/chat.js.
            // This must stay inline (not in the external .js file) because
            // <%= request.getContextPath() %> is a JSP scriptlet — it only
            // works inside files processed by Jasper, not in plain static
            // .js files served directly by the container.
            window.APP_CONTEXT_PATH = '<%= request.getContextPath() %>';
        </script>
        <script src="<%= request.getContextPath() %>/js/plugin.js"></script>
    </body>

    </html>