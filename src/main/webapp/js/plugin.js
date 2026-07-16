// ── AIS Assistant chat UI logic ─────────────────────────────────────
// Extracted from index.jsp for a cleaner file structure. This is a
// plain static JS file served directly by the container (not passed
// through Jasper/JSP), so it must not contain any JSP scriptlets
// (e.g. <%= ... %>). The one dynamic value it needs — the app's
// context path — is exposed as a global by a tiny inline <script>
// left in index.jsp (window.APP_CONTEXT_PATH), set just before this
// file is loaded.

const chatContainer = document.getElementById('chat-container');
const input          = document.getElementById('prompt-input');
const sendBtn        = document.getElementById('send-btn');
const loading        = document.getElementById('loading');
const quickPrompts   = document.getElementById('quick-prompts');

const ctx = window.APP_CONTEXT_PATH || '';

// Slash command that reveals the tools panel. Matches "/skill" with
// optional trailing text, case-insensitively, ignoring leading spaces.
const SKILL_COMMAND = /^\/skill\b/i;

let toolsLoaded = false;

// Set to true if the last /api/tools fetch failed, so the panel can show
// a real error instead of a misleading "no matching tools" message.
let toolsLoadError = false;

// Raw tool definitions from the last successful /api/tools fetch, kept
// around so Tab-autocomplete (see below) can match against them without
// re-fetching or re-reading the DOM.
let latestTools = [];

// State for the currently open "/skill" tools list.
//
//   currentMatches   — the ranked tool list currently shown (or last
//                       shown) in the panel, from rankTools().
//   selectedIndex    — which row in currentMatches is highlighted
//                       (.active). Arrow Up/Down move this; Tab/Enter/
//                       click apply whichever tool it points to.
//   appliedIndex     — the index (if any) that was last inserted into
//                       the input via Tab/Enter/click. Lets Tab tell
//                       "apply what's currently highlighted" (e.g. right
//                       after an Arrow Up/Down move) apart from "cycle
//                       forward" (a bare repeated Tab press with no
//                       navigation in between): if selectedIndex still
//                       equals appliedIndex, Tab advances first; if they
//                       differ (a fresh arrow move/click), Tab applies
//                       the highlighted row as-is. null = nothing
//                       applied yet in this query.
//   tabCyclingActive — true once the user has interacted with the list
//                       (via Tab, an arrow key, or a click). Lets Tab
//                       keep cycling through currentMatches even after
//                       the panel has been hidden (e.g. right after
//                       applying a match), until the user types
//                       something new or presses Escape, at which
//                       point it's reset to false.
let currentMatches = [];
let selectedIndex = 0;
let appliedIndex = null;
let tabCyclingActive = false;

// ── Load tools once on page load, but keep the panel hidden ───
// until the user actually asks for it via "/skill".
async function loadTools() {
    try {
        const resp = await fetch(ctx + '/api/tools');
        if (!resp.ok) throw new Error('Failed to load tools');

        latestTools = await resp.json();
        toolsLoaded = true;
        toolsLoadError = false;

    } catch (err) {
        console.error('Error loading tools:', err);
        latestTools = [];
        toolsLoaded = false;
        toolsLoadError = true;
    }
}

// ── Extract the text typed after "/skill" (the search query) ──────
function currentSkillQuery() {
    return input.value.trimStart().replace(SKILL_COMMAND, '').trim();
}

// ── Ranking: decide how well a tool matches the typed query ───────
// Higher score = better match. Returns null if the tool shouldn't be
// shown at all for this query.
//
//   3   exact match on tool name            (query === "hardcode_query")
//   2   tool name starts with the query     (query === "hardcode")
//   1   tool name contains the query        (query === "code_query")
//   0.5 sample prompt / description matches (query === "general info")
//   0   no query typed yet — show everything, unranked
//
// Ties keep the tools' original order from GET /api/tools (i.e. the
// registration order in MCPClientService.getToolDefs()), so the list
// is stable and predictable rather than jumping around.
function scoreTool(tool, query) {
    if (!query) return 0;

    const name = (tool.name || '').toLowerCase();
    if (name === query) return 3;
    if (name.startsWith(query)) return 2;
    if (name.includes(query)) return 1;

    const extra = ((tool.samplePrompt || '') + ' ' + (tool.description || '')).toLowerCase();
    if (extra.includes(query)) return 0.5;

    return null;
}

// Returns tools matching `query`, best match first. Rank 0 (the top of
// the list) is what Tab will complete to.
function rankTools(query) {
    const q = query.trim().toLowerCase();
    const scored = [];

    latestTools.forEach((tool, originalIndex) => {
        const score = scoreTool(tool, q);
        if (score !== null) {
            scored.push({ tool, score, originalIndex });
        }
    });

    scored.sort((a, b) => (b.score - a.score) || (a.originalIndex - b.originalIndex));
    return scored.map(s => s.tool);
}

// ── Render the "/skill" tools panel as a numbered list ─────────────
// e.g. "1. hardcode_query: get general info"
// The row at `selectedIndex` is highlighted as .active — that's the
// tool Tab/Enter will apply, and what Arrow Up/Down move between.
function renderSkillList(matches) {
    quickPrompts.classList.remove('loading', 'empty');
    quickPrompts.innerHTML = '';

    if (toolsLoadError) {
        quickPrompts.classList.add('empty');
        quickPrompts.innerHTML = '<span style="color:#e94560;"> Failed to load tools</span>';
        return;
    }

    if (matches.length === 0) {
        quickPrompts.classList.add('empty');
        quickPrompts.textContent = 'No matching tools — try a different name.';
        return;
    }

    const hint = document.createElement('div');
    hint.id = 'skill-hint';
    // innerHTML (not textContent) here is safe: this is a static literal,
    // not user input, and it needs to render the LineIcons <i> tags.
    hint.innerHTML = 'Click, or use <i class="fa-solid fa-arrow-up"></i>/<i class="fa-solid fa-arrow-down"></i> then Tab/Enter, to insert a skill\'s prompt.';
    quickPrompts.appendChild(hint);

    matches.forEach((tool, i) => {
        const btn = document.createElement('button');
        btn.className = 'quick-btn '
            + (tool.needsInput ? 'fill-only' : 'auto-send')
            + (i === selectedIndex ? ' active' : '');
        btn.setAttribute('data-name', tool.name);
        btn.setAttribute('data-index', i);
        btn.title = tool.samplePrompt || '';

        let html = '<span class="qindex">' + (i + 1) + '.</span>';
        html += '<span class="qname">' + escapeHtml(tool.name) + '</span>';

        const desc = tool.description || tool.samplePrompt || '';
        if (desc) {
            html += '<span class="qdesc">: ' + escapeHtml(desc) + '</span>';
        }
        if (tool.needsInput && tool.inputHint) {
            html += ' <span class="needs-input">+ ' + escapeHtml(tool.inputHint) + '</span>';
        }

        btn.innerHTML = html;
        quickPrompts.appendChild(btn);
    });
}

// Re-ranks against the current input text and re-renders the list.
// Called whenever the query changes (typing, or once tools finish
// loading while the panel is already open). Resets the highlighted
// row back to the top match, since the ranking itself just changed.
function refreshSkillPanel() {
    currentMatches = rankTools(currentSkillQuery());
    selectedIndex = 0;
    appliedIndex = null;
    renderSkillList(currentMatches);
    return currentMatches;
}

// ── Arrow Up/Down: move the highlighted row without re-ranking ─────
// Wraps around at both ends. Only meaningful while the list is open
// and has at least one match. Moving the selection does NOT apply it
// to the input — that still needs Tab, Enter, or a click.
function moveSkillSelection(delta) {
    if (currentMatches.length === 0) return;

    selectedIndex = (selectedIndex + delta + currentMatches.length) % currentMatches.length;
    tabCyclingActive = true;
    renderSkillList(currentMatches);
    scrollActiveRowIntoView();
}

function scrollActiveRowIntoView() {
    const active = quickPrompts.querySelector('.quick-btn.active');
    if (active) {
        active.scrollIntoView({ block: 'nearest' });
    }
}

// ── Show/hide the tools panel based on the current input value ──
// Called live on every keystroke so the panel appears the moment
// the user types "/skill" — no need to press Enter/Send.
function updateSkillTrigger() {
    const val = input.value.trimStart();
    if (!SKILL_COMMAND.test(val)) {
        quickPrompts.classList.remove('visible');
        return;
    }

    quickPrompts.classList.add('visible');

    if (!toolsLoaded) {
        quickPrompts.classList.add('loading');
        quickPrompts.textContent = '⏳ Loading tools...';
        loadTools().then(() => {
            if (quickPrompts.classList.contains('visible')) {
                refreshSkillPanel();
            }
        });
        return;
    }

    refreshSkillPanel();
}

function hideSkillPanel() {
    quickPrompts.classList.remove('visible');
}

// ── Fill the input with a tool's full sample prompt ───────────────
// Used by clicking a row, pressing Tab/Enter, or arrow-key navigation
// followed by Tab/Enter (see below). Typing "/skill" (optionally
// followed by a search term, e.g. "/skill historic") ranks tools by
// relevance (see scoreTool() above); Arrow Up/Down move the highlighted
// row within that ranked list; Tab/Enter/click apply whichever row is
// currently highlighted.

function applyToolPrompt(tool) {
    const prompt = tool.samplePrompt || tool.name;
    input.value = prompt;
    input.classList.add('prefilled');
    hideSkillPanel();

    if (tool.placeholder) {
        input.setAttribute('placeholder', tool.placeholder);
    }

    // Place cursor at end
    const len = input.value.length;
    input.setSelectionRange(len, len);
}

// True if Tab should be intercepted at all (used synchronously inside the
// keydown handler so preventDefault() can be called before any awaiting).
function shouldHandleTab() {
    return tabCyclingActive || SKILL_COMMAND.test(input.value.trimStart());
}

// Performs the actual autocomplete/cycle once we know Tab should be
// handled. Async because tools may still be loading on first use.
async function handleTabAutocomplete() {
    if (!tabCyclingActive) {
        // First Tab press: rank tools against "/skill <query>".
        if (!toolsLoaded) {
            await loadTools();
        }
        currentMatches = rankTools(currentSkillQuery());
        selectedIndex = 0;
        tabCyclingActive = true;
    } else if (currentMatches.length > 0 && selectedIndex === appliedIndex) {
        // Repeated Tab press with no Arrow Up/Down move in between:
        // the highlighted row is what we already inserted last time,
        // so advance to the next-best match instead of re-applying it.
        selectedIndex = (selectedIndex + 1) % currentMatches.length;
    }
    // else: the user moved the highlight (Arrow Up/Down) since the last
    // Tab/Enter/click, so apply whatever is highlighted now as-is,
    // without advancing past it.

    if (currentMatches.length === 0) {
        return; // nothing to complete; Tab press already consumed
    }
    appliedIndex = selectedIndex;
    applyToolPrompt(currentMatches[selectedIndex]);
}

// ── Skill list row click / keyboard selection handlers ─────────────
// Clicking a row immediately expands the input to that tool's full
// sample prompt — same result as navigating to it with Arrow Up/Down
// and then pressing Tab/Enter, just in one step.
quickPrompts.addEventListener('click', (e) => {
    const btn = e.target.closest('.quick-btn');
    if (!btn) return;

    const idx = parseInt(btn.getAttribute('data-index'), 10);
    if (!Number.isNaN(idx) && currentMatches[idx]) {
        selectedIndex = idx;
    }

    const name = btn.getAttribute('data-name');
    const tool = latestTools.find(t => t.name === name);
    if (!tool) return;

    tabCyclingActive = true;
    appliedIndex = selectedIndex;
    applyToolPrompt(tool);
});

// Pulls the map block out of a message's HTML and returns it as a
// detached DOM node, or null if the message has none. The server
// (formatSingleLocation() in OllamaService.java) marks the AIS link +
// iframe block with class="location-map-block", still inline at the
// end of the message content it returns — this is the only thing the
// backend needs to do; everything else (positioning it beside the
// message instead of inside it) happens here on the client.
function extractMapBlock(messageDiv) {
    const markers = Array.from(messageDiv.querySelectorAll('.location-map-block'));

    if (markers.length === 0) {
        return null;
    }

    markers.forEach(marker => marker.remove());

    // One location: preserve the existing sticky-map behavior.
    if (markers.length === 1) {
        const marker = markers[0];
        marker.classList.remove('location-map-block');
        marker.classList.add('location-map-sticky');
        return marker;
    }

    // Multiple locations: create one sticky wrapper with tabs.
    const wrapper = document.createElement('div');
    wrapper.className = 'location-map-sticky location-map-multiple';
    const controls = document.createElement('div');
    controls.className = 'location-map-tabs';
    const panels = document.createElement('div');
    panels.className = 'location-map-panels';
	
    markers.forEach((marker, index) => {
        marker.classList.remove('location-map-block');
        marker.classList.add('location-map-panel');

        marker.hidden = index !== 0;

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'location-map-tab' + (index === 0 ? ' active' : '');
        btn.textContent = getLocCdFromIframe(marker.querySelector('iframe')) || `Location ${i + 1}`;
        button.addEventListener('click',() => {
                const allButtons = controls.querySelectorAll('.location-map-tab');
                const allPanels = panels.querySelectorAll('.location-map-panel');
                allButtons.forEach(item => item.classList.toggle('active', b === btn));
                allPanels.forEach(item => {item.hidden = p !== marker;});
            }
        );
        controls.appendChild(button);
        panels.appendChild(marker);
    });

    wrapper.appendChild(controls);
    wrapper.appendChild(panels);

    return wrapper;
}

function getLocCdFromIframe(iframe) {
    if (!iframe) {
        return '';
    }

    try {
        const url = new URL(iframe.getAttribute('src'),window.location.href);
        return (url.searchParams.get('locCd') || '').trim().toUpperCase();
    } catch (error) {
        console.warn('Could not read iframe location code',error);
        return '';
    }
}
// ── "No feature" handling for the map iframe ───────────────────────
// plugin.html (loaded inside the map <iframe>) runs as a separate
// document, so it can't reach into this page's DOM directly to update
// the "View in AIS Asset Search" button when its own ArcGIS query
// finds nothing. Instead it posts a message up to this window; we
// listen for it here and relabel the matching button.
//
// Matching is done by locCd, extracted from the iframe's own src
// query string, since a page can show more than one location-map
// block (e.g. multiple tool-call results in one response) and we
// must update only the button paired with the iframe that reported
// "no-feature", not every button on the page.
window.addEventListener('message', (event) => {
    if (event.origin !== window.location.origin) return; // ignore cross-origin noise
    const data = event.data;
    if (!data || data.source !== 'ais-plugin-map') return;

    // Identify the iframe by event.source (its contentWindow), NOT by
    // matching locCd in the src attribute. Two different messages/map
    // blocks can legitimately point at the very same locCd (e.g. two
    // separate queries in the chat both resolving to the same location),
    // so document.querySelector('iframe[src*="locCd=..."]') would always
    // return the FIRST matching iframe in the whole document — silently
    // re-applying "No feature" to an already-updated button from an
    // earlier message while the actual iframe that just sent this event
    // (and its still-live button) never gets touched at all.
    let iframe = null;
    const allIframes = document.querySelectorAll('iframe');
    for (const el of allIframes) {
        if (el.contentWindow === event.source) {
            iframe = el;
            break;
        }
    }
    if (!iframe) return;

    // Look for the paired "View in AIS Asset Search" button in the
    // widest scope that reliably contains both the button and the
    // iframe, regardless of the exact wrapper markup OllamaService
    // happens to emit for this response:
    //   1. .location-map-sticky — if extractMapBlock() already moved
    //      the map into its own sticky sidebar (the normal case, once
    //      the server wraps both in <div class="location-map-block">).
    //   2. .message / .message-row — the whole chat message bubble, as
    //      a fallback for older/unwrapped HTML where the button and
    //      iframe are two separate sibling <div>s instead of one
    //      shared block, so extractMapBlock() had nothing to move and
    //      the iframe never got re-parented into .location-map-sticky.
	const panel =
	    iframe.closest('.location-map-panel')
	    || iframe.closest('.location-map-sticky')
	    || iframe.closest('.message-row')
	    || iframe.closest('.message')
	    || iframe.parentElement;
    const btn = panel ? panel.querySelector('.ais-detail-btn') : null;
    if (!btn) {
        console.warn('ais-plugin-map: no .ais-detail-btn found for locCd', data.locCd);
        return;
    }
    if (data.status === 'no-feature') {
        // Turn the link into plain, non-clickable status text: drop the
        // redirect entirely (no href) and swap the label.
		if(btn){
			const span = document.createElement('span');
			span.className = btn.className; // keep existing styling
			span.textContent = 'No feature for ' + data.locCd;
			btn.replaceWith(span);
		}
		replaceMapWithStatus(iframe,"No map feature was found for " + data.locCd + ".");
        
    }
	
	if(data.status === 'error'){
		replaceMapWithStatus(iframe, data.message ||
			('The map service is unavailable for '
			+ data.locCd
			+ '. The location code was received correctly.')
		);
	}
    // 'ok' / 'error' statuses currently need no UI change here.
});

function replaceMapWithStatus(iframe, message){
	if(!iframe){
		return;
	}
	const status = document.createElement('div');
	status.className = 'location-map-error';
	status.textContent = message;
	iframe.replaceWith(status);
}

function appendMessage(cls, html) {
    const div = document.createElement('div');
    div.className = 'message ' + cls;
    div.innerHTML = html;

    const mapBlock = extractMapBlock(div);

    if (!mapBlock) {
        // No map: append the message on its own, exactly as before.
        chatContainer.appendChild(div);
        chatContainer.scrollTop = chatContainer.scrollHeight;
        return div;
    }

    // Map present: the message and the map become true siblings inside
    // a .message-row flex row, so the map sits visually to the right of
    // the message bubble instead of being nested inside it.
    div.classList.add('has-map');

    const row = document.createElement('div');
    row.className = 'message-row';
    row.appendChild(div);
    row.appendChild(mapBlock);

    chatContainer.appendChild(row);
    chatContainer.scrollTop = chatContainer.scrollHeight;
    return div;
}

function appendTiming(ms) {
    const div = document.createElement('div');
    div.className = 'timing';
    // innerHTML (not textContent) is required here: textContent renders
    // HTML tags as literal text ("<i class=...>"), not as an icon. This
    // is safe since the string is a static literal, not user input.
    div.innerHTML = '<i class="fa-solid fa-stopwatch"></i> ' + (ms / 1000).toFixed(2) + 's';
    chatContainer.appendChild(div);
}

async function sendMessage() {
    const prompt = input.value.trim();
    if (!prompt) return;

    // ── "/skill" command: reveal the tools panel, don't hit the LLM ──
    // Covers the case where the user types "/skill ..." and presses
    // Enter/Send instead of just reading the live-typing panel.
    if (SKILL_COMMAND.test(prompt)) {
        if (!toolsLoaded) {
            await loadTools();
        }
        quickPrompts.classList.add('visible');
        refreshSkillPanel();
        input.focus();
        return;
    }

    // Reset input state
    input.classList.remove('prefilled');
    input.setAttribute('placeholder', 'Enter location code or question... (type /skill for tools)');
    hideSkillPanel();

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
            appendMessage('assistant', 'Error: ' + escapeHtml(data.error));
        } else {
            appendMessage('assistant', data.answer);
            appendTiming(data.elapsedMs);
        }

    } catch (err) {
        appendMessage('assistant', 'Network error: ' + err.message);
    } finally {
        sendBtn.disabled = false;
        loading.style.display = 'none';
    }
}

// Send on button click
sendBtn.addEventListener('click', sendMessage);

// Send on Enter, navigate the "/skill" list with Arrow Up/Down,
// autocomplete on Tab or Enter (while the list is open), dismiss the
// panel on Escape.
input.addEventListener('keydown', e => {
    const skillPanelOpen = quickPrompts.classList.contains('visible')
        && currentMatches.length > 0;

    if (e.key === 'ArrowDown' && skillPanelOpen) {
        e.preventDefault();
        moveSkillSelection(1);
        return;
    }
    if (e.key === 'ArrowUp' && skillPanelOpen) {
        e.preventDefault();
        moveSkillSelection(-1);
        return;
    }
    if (e.key === 'Enter' && !e.shiftKey) {
        if (skillPanelOpen) {
            // Apply whichever row Arrow Up/Down (or the default ranking)
            // currently has highlighted, instead of sending "/skill..."
            // straight to the chat.
            e.preventDefault();
            tabCyclingActive = true;
            appliedIndex = selectedIndex;
            applyToolPrompt(currentMatches[selectedIndex]);
            return;
        }
        sendMessage();
        return;
    }
    if (e.key === 'Escape') {
        hideSkillPanel();
        tabCyclingActive = false;
        appliedIndex = null;
        return;
    }
    if (e.key === 'Tab' && shouldHandleTab()) {
        e.preventDefault();
        handleTabAutocomplete();
    }
});

// Live "/skill" detection + remove prefilled highlight when user edits.
// Any real keystroke invalidates the current Tab-cycling / arrow-key
// session so the next Tab press (or the next panel render) starts a
// fresh ranked match against the newly typed text.
input.addEventListener('input', () => {
    input.classList.remove('prefilled');
    updateSkillTrigger();
    tabCyclingActive = false;
    appliedIndex = null;
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
                <span class="tool-badge">TOOL</span>
                <strong>${escapeHtml(tc.name)}</strong>
                <span class="tool-hint">click to expand</span>
            </summary>
            <div class="tool-body">
                <div class="tool-section">
                    <b>Args:</b>
                    <pre>${escapeHtml(prettyArgs)}</pre>
                </div>
                <div class="tool-section">
                    <b>Result:</b>
                    <pre>${escapeHtml(prettyResult)}</pre>
                </div>
            </div>
        </details>
    `;
}

// ── Initialize ───────────────────────────────────────────────
loadTools();
