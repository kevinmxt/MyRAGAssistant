// ==================== 环境管理 ====================

const envCards = document.getElementById('envCards');
const envSummary = document.getElementById('envSummary');
const tabBtns = document.querySelectorAll('.tab-btn');
const tabPanels = document.querySelectorAll('.tab-panel');

let envSseSource = null;
let envData = { dependencies: [], summary: {}, installInProgress: false };
let envConnected = false;

// ---- Tab 切换 ----
tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
        const target = btn.dataset.tab;
        tabBtns.forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        tabPanels.forEach(p => {
            p.classList.remove('active');
            p.style.display = 'none';
        });
        if (target === 'chat') {
            document.getElementById('chatPanel').classList.add('active');
            document.getElementById('chatPanel').style.display = '';
        } else {
            document.getElementById('envPanel').classList.add('active');
            document.getElementById('envPanel').style.display = '';
            if (!envConnected) connectEnvSse();
        }
    });
});

// ---- SSE 连接 ----
function connectEnvSse() {
    if (envSseSource) {
        envSseSource.close();
    }
    envSseSource = new EventSource('/api/env/stream');

    envSseSource.addEventListener('status', e => {
        const data = JSON.parse(e.data);
        envData = data;
        envConnected = true;
        renderSummary();
        renderCards();
    });

    envSseSource.addEventListener('check-result', e => {
        const result = JSON.parse(e.data);
        updateOrAddResult(result);
        renderSummary();
        renderCards();
    });

    envSseSource.addEventListener('check-start', e => {
        const data = JSON.parse(e.data);
        envSummary.innerHTML = '<span class="env-summary-text">正在检测 ' + data.total + ' 项依赖...</span>';
        envCards.innerHTML = '';
        for (let i = 0; i < data.total; i++) {
            const card = document.createElement('div');
            card.className = 'env-card checking';
            card.innerHTML = '<span class="env-icon">⟳</span><span class="env-name">检测中...</span>';
            envCards.appendChild(card);
        }
    });

    envSseSource.addEventListener('install-log', e => {
        const data = JSON.parse(e.data);
        const logEl = document.getElementById('installLog-' + data.name);
        if (logEl) {
            logEl.innerHTML += escapeHtml(data.line) + '\n';
            logEl.scrollTop = logEl.scrollHeight;
        }
    });

    envSseSource.addEventListener('install-done', e => {
        const data = JSON.parse(e.data);
        const logEl = document.getElementById('installLog-' + data.name);
        if (logEl) {
            logEl.innerHTML += '\n--- ' + (data.success ? '安装完成' : '安装失败') + ' ---\n';
            logEl.scrollTop = logEl.scrollHeight;
        }
    });

    envSseSource.onerror = () => {
        envConnected = false;
        // SSE will auto-reconnect
    };

    envSseSource.onopen = () => {
        envConnected = true;
    };
}

function updateOrAddResult(result) {
    const existing = envData.dependencies.findIndex(d => d.name === result.name);
    if (existing >= 0) {
        envData.dependencies[existing] = result;
    } else {
        envData.dependencies.push(result);
    }
}

// ---- 渲染 ----
function renderSummary() {
    const s = envData.summary;
    if (!s || s.total === 0) {
        envSummary.innerHTML = '<span class="env-summary-text">环境检测中...</span>';
        return;
    }
    let parts = [];
    if (s.ok > 0) parts.push('<span class="env-count ok">' + s.ok + ' 正常</span>');
    if (s.missing > 0) parts.push('<span class="env-count missing">' + s.missing + ' 缺失</span>');
    if (s.error > 0) parts.push('<span class="env-count error">' + s.error + ' 错误</span>');
    if (s.skipped > 0) parts.push('<span class="env-count skipped">' + s.skipped + ' 可选缺失</span>');
    if (parts.length === 0) parts.push('<span>无依赖信息</span>');
    let html = parts.join(' ');
    if (envData.checkDurationMs > 0) {
        html += ' <span class="env-duration">(' + envData.checkDurationMs + 'ms)</span>';
    }
    envSummary.innerHTML = html;
}

function renderCards() {
    if (!envData.dependencies || envData.dependencies.length === 0) {
        envCards.innerHTML = '<div class="env-empty">等待检测结果...</div>';
        return;
    }

    let html = '';
    envData.dependencies.forEach(dep => {
        const statusClass = dep.status.toLowerCase();
        let icon = '';
        let actions = '';

        switch (dep.status) {
            case 'OK':
                icon = '✅';
                if (dep.canAutoInstall) {
                    actions = '<button class="env-btn env-btn-outline" onclick="recheckDep(\'' + dep.name + '\')">重检</button>';
                }
                break;
            case 'MISSING':
                icon = '❌';
                if (dep.canAutoInstall && !envData.installInProgress) {
                    actions = '<button class="env-btn env-btn-primary" onclick="installDep(\'' + dep.name + '\')">安装</button>';
                } else if (dep.name === 'milvus') {
                    actions = '<button class="env-btn env-btn-primary" onclick="reconnectMilvus()">重试连接</button>';
                }
                actions += '<button class="env-btn env-btn-outline" onclick="showInstallGuide(\'' + dep.name + '\')">安装指引</button>';
                break;
            case 'SKIPPED':
                icon = '⚠️';
                actions = '<button class="env-btn env-btn-outline" onclick="showInstallGuide(\'' + dep.name + '\')">安装指引</button>';
                break;
            case 'INSTALLING':
                icon = '⟳';
                break;
            case 'ERROR':
                icon = '❌';
                if (dep.canAutoInstall && !envData.installInProgress) {
                    actions = '<button class="env-btn env-btn-primary" onclick="installDep(\'' + dep.name + '\')">重试安装</button>';
                }
                break;
        }

        let versionHtml = dep.version ? '<span class="env-version">' + escapeHtml(dep.version) + '</span>' : '';

        html += '<div class="env-card ' + statusClass + '">';
        html += '<div class="env-card-header">';
        html += '<span class="env-icon">' + icon + '</span>';
        html += '<span class="env-name">' + escapeHtml(dep.name) + '</span>';
        html += versionHtml;
        html += '<span class="env-category">' + escapeHtml(dep.category) + '</span>';
        html += '</div>';
        html += '<div class="env-card-body">';
        html += '<div class="env-message">' + escapeHtml(dep.message || '') + '</div>';
        if (actions) {
            html += '<div class="env-actions">' + actions + '</div>';
        }
        // 安装指引区域
        html += '<div class="env-install-guide" id="guide-' + dep.name + '" style="display:none;"></div>';
        // 安装日志区域
        html += '<pre class="env-install-log" id="installLog-' + dep.name + '" style="display:none;"></pre>';
        html += '</div>';
        html += '</div>';
    });
    envCards.innerHTML = html;

    // 如果正在安装，恢复日志显示
    if (envData.installInProgress) {
        envData.dependencies.forEach(dep => {
            if (dep.status === 'INSTALLING') {
                const logEl = document.getElementById('installLog-' + dep.name);
                if (logEl) logEl.style.display = 'block';
            }
        });
    }
}

// ---- 操作 ----
async function installDep(name) {
    try {
        const resp = await fetch('/api/env/install', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name })
        });
        if (!resp.ok) {
            const err = await resp.json();
            alert('安装失败: ' + (err.error || resp.statusText));
            return;
        }
        // 显示日志区域
        const logEl = document.getElementById('installLog-' + name);
        if (logEl) {
            logEl.style.display = 'block';
            logEl.innerHTML = '';
        }
    } catch (e) {
        alert('请求失败: ' + e.message);
    }
}

async function reconnectMilvus() {
    try {
        const resp = await fetch('/api/env/reconnect-milvus', { method: 'POST' });
        if (!resp.ok) {
            const err = await resp.json();
            alert('重连失败: ' + (err.error || resp.statusText));
        }
    } catch (e) {
        alert('请求失败: ' + e.message);
    }
}

async function recheckDep(name) {
    try {
        const resp = await fetch('/api/env/check', { method: 'POST' });
        if (!resp.ok) {
            alert('重检失败');
        }
    } catch (e) {
        alert('请求失败: ' + e.message);
    }
}

function showInstallGuide(name) {
    const guideEl = document.getElementById('guide-' + name);
    if (!guideEl) return;
    if (guideEl.style.display === 'block') {
        guideEl.style.display = 'none';
        return;
    }

    const platform = navigator.platform || '';
    const isWin = platform.includes('Win');
    const isMac = platform.includes('Mac');
    const isLinux = !isWin && !isMac;

    let cmds = [];
    switch (name) {
        case 'python':
            cmds = [
                isWin ? 'winget install Python.Python.3.12' : '',
                isMac ? 'brew install python@3.12' : '',
                isLinux ? 'sudo apt install python3.12 python3-pip' : '',
                '下载页面: https://www.python.org/downloads/'
            ];
            break;
        case 'milvus':
            cmds = ['docker compose up -d', '或手动启动 Milvus + etcd + MinIO'];
            break;
        case 'pandoc':
            cmds = [
                isWin ? 'winget install JohnMacFarlane.Pandoc' : '',
                isMac ? 'brew install pandoc' : '',
                isLinux ? 'sudo apt install pandoc' : '',
                '下载页面: https://pandoc.org/installing.html'
            ];
            break;
        case 'tesseract':
            cmds = [
                isWin ? 'winget install UB-Mannheim.TesseractOCR' : '',
                isMac ? 'brew install tesseract' : '',
                isLinux ? 'sudo apt install tesseract-ocr' : '',
                '下载页面: https://github.com/UB-Mannheim/tesseract/wiki'
            ];
            break;
        case 'lightrag':
            cmds = ['python -m pip install lightrag requests',
                'Python 环境需已安装 pip'];
            break;
        case 'model-files':
            cmds = ['精排模型: 应用启动后自动后台下载到 models/bge-reranker-v2-m3/',
                'LightRAG 嵌入模型: 需手动放置模型文件到 multiRecall.lightrag.embeddingModelPath 配置的路径'];
            break;
        default:
            cmds = ['请参阅 README.md 中的安装说明'];
    }

    let html = '<div class="install-guide-title">安装指引</div><ul>';
    cmds.filter(c => c).forEach(c => {
        html += '<li><code>' + escapeHtml(c) + '</code></li>';
    });
    html += '</ul>';
    guideEl.innerHTML = html;
    guideEl.style.display = 'block';
}
