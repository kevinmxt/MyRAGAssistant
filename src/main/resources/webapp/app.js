// DOM elements
const chatMessages = document.getElementById('chatMessages');
const chatInput = document.getElementById('chatInput');
const sendBtn = document.getElementById('sendBtn');
const ingestBtn = document.getElementById('ingestBtn');
const browseBtn = document.getElementById('browseBtn');
const ingestDir = document.getElementById('ingestDir');
const ingestStatus = document.getElementById('ingestStatus');
const documentList = document.getElementById('documentList');
const storeStatus = document.getElementById('storeStatus');
const supportedFormats = document.getElementById('supportedFormats');

// Browse modal elements
const browseModal = document.getElementById('browseModal');
const browseBreadcrumb = document.getElementById('browseBreadcrumb');
const browseDirList = document.getElementById('browseDirList');
const browseManualPath = document.getElementById('browseManualPath');
const browseConfirmBtn = document.getElementById('browseConfirmBtn');

let isWaiting = false;
let browseCurrentPath = '';
let browseParentPath = null;

// Supported file extensions (synced with server defaults)
const DEFAULT_SUPPORTED_EXTENSIONS = [
    '.txt', '.pdf', '.docx', '.doc', '.png', '.jpg', '.jpeg',
    '.md', '.html', '.csv', '.json', '.xlsx', '.pptx'
];

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    refreshDocuments();
    showSupportedFormats();
    refreshKgStatus();
});

// Display supported formats hint
function showSupportedFormats() {
    supportedFormats.textContent = '支持格式：' + DEFAULT_SUPPORTED_EXTENSIONS.join(', ');
}

// Handle Enter key (send on Enter, newline on Shift+Enter)
function handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
    }
}

// Auto-resize textarea
chatInput.addEventListener('input', () => {
    chatInput.style.height = 'auto';
    chatInput.style.height = Math.min(chatInput.scrollHeight, 120) + 'px';
});

async function sendMessage() {
    const query = chatInput.value.trim();
    if (!query || isWaiting) return;

    // Clear input
    chatInput.value = '';
    chatInput.style.height = 'auto';

    // Hide welcome message
    const welcome = document.querySelector('.welcome-message');
    if (welcome) welcome.remove();

    // Add user message
    addMessage('user', query);

    // Show loading
    setWaiting(true);
    const loadingEl = addLoadingDots();

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query })
        });

        if (!response.ok) {
            const errorData = await response.json();
            throw new Error(errorData.error || '请求失败');
        }

        const data = await response.json();

        // Remove loading dots
        loadingEl.remove();

        // Add assistant message with sources
        addAssistantMessage(data.answer, data.sources);
    } catch (error) {
        loadingEl.remove();
        addMessage('assistant', '抱歉，处理你的问题时出错了：' + error.message);
    } finally {
        setWaiting(false);
    }
}

function addMessage(role, content) {
    const div = document.createElement('div');
    div.className = 'message ' + role;
    div.textContent = content;
    chatMessages.appendChild(div);
    scrollToBottom();
}

function addAssistantMessage(answer, sources) {
    const div = document.createElement('div');
    div.className = 'message assistant';

    // Simple markdown rendering
    let html = renderMarkdown(answer);

    // Add sources if available
    if (sources && sources.length > 0) {
        html += '<div class="sources">';
        html += '<div class="sources-label">📎 参考来源：</div>';
        sources.forEach(s => {
            const shortName = s.fileName.replace(/\\/g, '/').split('/').pop();
            html += '<div class="source-item">';
            html += '<span class="source-file">' + escapeHtml(shortName) + '</span>';
            html += '<span class="source-score">(相关度: ' + (s.score * 100).toFixed(0) + '%)</span>';
            html += '</div>';
        });
        html += '</div>';
    }

    div.innerHTML = html;
    chatMessages.appendChild(div);
    scrollToBottom();
}

function renderMarkdown(text) {
    let html = escapeHtml(text);

    // Code blocks (``` ... ```)
    html = html.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>');

    // Inline code (`...`)
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

    // Bold (**...**)
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');

    // Italic (*...*)
    html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');

    // Newlines to <br>
    html = html.replace(/\n/g, '<br>');

    return html;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function addLoadingDots() {
    const div = document.createElement('div');
    div.className = 'loading-dots';
    div.innerHTML = '<span></span><span></span><span></span>';
    chatMessages.appendChild(div);
    scrollToBottom();
    return div;
}

function setWaiting(waiting) {
    isWaiting = waiting;
    sendBtn.disabled = waiting;
    chatInput.disabled = waiting;
}

async function ingestDocuments() {
    const directory = ingestDir.value.trim();
    if (!directory) {
        showIngestStatus('请输入文档目录路径', 'error');
        return;
    }

    ingestBtn.disabled = true;
    browseBtn.disabled = true;
    showIngestStatus('正在摄入文档...', 'loading');

    try {
        const response = await fetch('/api/ingest', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ directory })
        });

        const data = await response.json();

        if (response.ok && data.success) {
            showIngestStatus('成功！处理了 ' + data.filesProcessed + ' 个文件，创建了 ' + data.segmentsCreated + ' 个片段。', 'success');
            refreshDocuments();
        } else {
            showIngestStatus('失败：' + (data.error || data.message || '未知错误'), 'error');
        }
    } catch (error) {
        showIngestStatus('请求失败：' + error.message, 'error');
    } finally {
        ingestBtn.disabled = false;
        browseBtn.disabled = false;
    }
}

function showIngestStatus(message, type) {
    ingestStatus.textContent = message;
    ingestStatus.className = 'status-message ' + type;
}

async function refreshDocuments() {
    documentList.innerHTML = '<div class="empty-state">加载中...</div>';

    try {
        const response = await fetch('/api/documents');
        if (!response.ok) throw new Error('请求失败');

        const documents = await response.json();

        if (documents.length === 0) {
            documentList.innerHTML = '<div class="empty-state">暂无已索引文档</div>';
            storeStatus.textContent = '存储状态: 空';
        } else {
            let totalSegments = 0;
            let html = '';
            documents.forEach(doc => {
                totalSegments += doc.segmentCount;
                html += '<div class="doc-item">';
                html += '<div class="doc-name">' + escapeHtml(doc.fileName) + '</div>';
                html += '<div class="doc-meta">' + doc.segmentCount + ' 个片段';
                if (doc.fileType) {
                    html += ' · ' + escapeHtml(doc.fileType);
                }
                html += '</div>';
                if (doc.directory) {
                    html += '<div class="doc-meta">' + escapeHtml(doc.directory) + '</div>';
                }
                html += '<button class="kg-btn-small" onclick="buildKgForDocument(\'' +
                    escapeAttr(escapeHtml(doc.fileName)) + '\')" title="构建知识图谱">构建图谱</button>';
                html += '</div>';
            });
            documentList.innerHTML = html;
            storeStatus.textContent = '存储状态: ' + documents.length + ' 个文档, ' + totalSegments + ' 个片段';
        }
    } catch (error) {
        documentList.innerHTML = '<div class="empty-state">加载失败：' + escapeHtml(error.message) + '</div>';
        storeStatus.textContent = '存储状态: 错误';
    }
}

function scrollToBottom() {
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

// ==================== Directory Browser ====================

/**
 * Open the directory browser modal and load the initial directory.
 */
async function openBrowser() {
    browseModal.style.display = 'flex';
    // Start from the current input value, or root
    const currentInput = ingestDir.value.trim();
    if (currentInput) {
        await navigateTo(currentInput);
    } else {
        await navigateTo('');
    }
}

/**
 * Close the directory browser modal.
 */
function closeBrowser() {
    browseModal.style.display = 'none';
}

/**
 * Navigate to a specific directory path.
 */
async function navigateTo(dirPath) {
    browseDirList.innerHTML = '<div class="empty-state">加载中...</div>';
    browseCurrentPath = dirPath;

    try {
        const response = await fetch('/api/browse', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: dirPath })
        });

        if (!response.ok) {
            const err = await response.json();
            browseDirList.innerHTML = '<div class="empty-state">错误：' + escapeHtml(err.error || '请求失败') + '</div>';
            return;
        }

        const data = await response.json();
        browseCurrentPath = data.currentPath;
        browseParentPath = data.parentPath;

        renderBreadcrumb(data.currentPath, data.parentPath);
        renderDirectoryList(data.directories, data.currentPath);
        browseManualPath.value = data.currentPath || '';
        browseConfirmBtn.textContent = data.currentPath ? '选择当前目录' : '选择根目录';
    } catch (error) {
        browseDirList.innerHTML = '<div class="empty-state">请求失败：' + escapeHtml(error.message) + '</div>';
    }
}

/**
 * Render breadcrumb navigation from a path.
 */
function renderBreadcrumb(currentPath, parentPath) {
    let html = '';

    if (currentPath) {
        // Split path into parts for breadcrumb
        const parts = currentPath.replace(/\\/g, '/').split('/').filter(p => p);
        if (parts.length === 0) {
            // It's a root drive like "C:"
            html += '<span class="breadcrumb-item" onclick="navigateTo(\'\')">根目录</span>';
            html += '<span class="breadcrumb-separator">/</span>';
            html += '<span class="breadcrumb-current">' + escapeHtml(currentPath) + '</span>';
        } else {
            // Build breadcrumb with clickable segments
            let accumulatedPath = '';
            html += '<span class="breadcrumb-item" onclick="navigateTo(\'\')">根目录</span>';

            for (let i = 0; i < parts.length; i++) {
                html += '<span class="breadcrumb-separator">/</span>';
                // On Windows, first part might be "C:"
                if (i === 0 && parts[i].includes(':')) {
                    accumulatedPath = parts[i] + '/';
                } else {
                    accumulatedPath += '/' + parts[i];
                }

                if (i === parts.length - 1) {
                    html += '<span class="breadcrumb-current">' + escapeHtml(parts[i]) + '</span>';
                } else {
                    html += '<span class="breadcrumb-item" onclick="navigateTo(\'' +
                        escapeAttr(accumulatedPath) + '\')">' + escapeHtml(parts[i]) + '</span>';
                }
            }
        }
    } else {
        html += '<span class="breadcrumb-current">根目录</span>';
    }

    browseBreadcrumb.innerHTML = html;
}

/**
 * Render the directory list.
 */
function renderDirectoryList(directories, currentPath) {
    if (!directories || directories.length === 0) {
        browseDirList.innerHTML = '<div class="empty-state">此目录下没有子目录</div>';
        return;
    }

    let html = '';
    directories.forEach(dir => {
        const dirName = dir.replace(/\\/g, '/').split('/').pop() || dir;
        html += '<div class="dir-item" ondblclick="navigateTo(\'' + escapeAttr(dir) + '\')" onclick="selectBrowseDir(\'' + escapeAttr(dir) + '\')">';
        html += '<span class="dir-icon">📁</span>';
        html += '<span class="dir-name">' + escapeHtml(dirName) + '</span>';
        html += '</div>';
    });
    browseDirList.innerHTML = html;
}

/**
 * Select a directory by clicking (highlight it and update manual input).
 */
function selectBrowseDir(dirPath) {
    browseManualPath.value = dirPath;
    // Highlight selected item
    document.querySelectorAll('.dir-item').forEach(item => item.style.background = '');
    event.currentTarget.style.background = '#dbeafe';
}

/**
 * Confirm and use the currently browsed directory.
 */
function selectCurrentDir() {
    const path = browseCurrentPath || browseManualPath.value.trim();
    if (path) {
        ingestDir.value = path;
    }
    closeBrowser();
}

/**
 * Use the manually entered path.
 */
function selectManualPath() {
    const path = browseManualPath.value.trim();
    if (path) {
        ingestDir.value = path;
    }
    closeBrowser();
}

/**
 * Escape a string for use in HTML attribute values.
 */
function escapeAttr(str) {
    return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '&quot;');
}

// ==================== 知识图谱构建 ====================

async function buildKgForDocument(docName) {
    try {
        const response = await fetch('/api/kg/build/' + encodeURIComponent(docName), {
            method: 'POST'
        });
        const data = await response.json();
        if (data.success) {
            alert('图谱构建已触发：' + docName);
        } else {
            alert('构建失败：' + JSON.stringify(data.status));
        }
        refreshKgStatus();
    } catch (error) {
        alert('请求失败：' + error.message);
    }
}

async function buildKgForCurrentDir() {
    const dir = ingestDir.value.trim() || '';
    try {
        const response = await fetch('/api/kg/build?path=' + encodeURIComponent(dir), {
            method: 'POST'
        });
        const data = await response.json();
        if (data.success) {
            alert('批量构建已触发，状态：' + data.status.buildStatus);
        } else {
            alert('构建失败：' + JSON.stringify(data.status));
        }
        refreshKgStatus();
    } catch (error) {
        alert('请求失败：' + error.message);
    }
}

async function refreshKgStatus() {
    try {
        const response = await fetch('/api/kg/status');
        if (!response.ok) throw new Error('not available: ' + response.status);
        const data = await response.json();
        const statusEl = document.getElementById('kgGlobalStatus');
        const actionsEl = document.getElementById('kgActions');
        if (statusEl && actionsEl) {
            actionsEl.style.display = 'block';
            if (data.built) {
                statusEl.textContent = '图谱就绪 (' + (data.indexedDocuments || []).length + ' 个文档)';
                statusEl.className = 'kg-status ready';
            } else {
                statusEl.textContent = '状态: ' + (data.buildStatus || '未构建');
                statusEl.className = 'kg-status';
            }
        }
    } catch (e) {
        // KG not available — hide all KG UI
        document.querySelectorAll('.kg-btn-small').forEach(b => b.style.display = 'none');
        const actionsEl = document.getElementById('kgActions');
        if (actionsEl) actionsEl.style.display = 'none';
    }
}
