(() => {
    'use strict';

    const root = document.getElementById('balance-mock');
    const q = selector => root.querySelector(selector);
    const all = selector => root.querySelectorAll(selector);
    const number = new Intl.NumberFormat('ru-RU');
    const ratio = new Intl.NumberFormat('ru-RU', {maximumFractionDigits: 1});
    const percentage = new Intl.NumberFormat('ru-RU', {style: 'percent', maximumFractionDigits: 1});
    const date = new Intl.DateTimeFormat('ru-RU', {dateStyle: 'short', timeStyle: 'short'});
    const titles = {report: 'Сравнение стратегий', checks: 'Критические проверки', pivot: 'Сводная таблица', datasets: 'Наборы заявок', history: 'История запусков'};
    const statuses = {PENDING: 'В очереди', RUNNING: 'Выполняется', COMPLETED: 'Завершён', FAILED: 'Ошибка'};
    const stageLabels = {QUEUE: 'Очередь', NORMALIZATION: 'Нормализация', COMPARISON: 'Сравнение', CHECKS: 'Проверки', FACTS: 'Подготовка фактов', IMPORT: 'Импорт'};
    const stageStatuses = {PENDING: 'Ожидает', RUNNING: 'Выполняется', COMPLETED: 'Готово',
        FAILED: 'Ошибка', SKIPPED: 'Пропущено'};
    const groups = {
        all: ['Все заявки', {}],
        diff: ['Заявки с отличиями', {hasDiff: true}],
        changed: ['Решение изменилось', {decisionChanged: true}],
        aa: ['Одобрение → одобрение', {decisionBefore: 'APPROVE', decisionAfter: 'APPROVE'}, 'approveToApprove'],
        ad: ['Одобрение → отказ', {decisionBefore: 'APPROVE', decisionAfter: 'REJECT'}, 'approveToReject'],
        da: ['Отказ → одобрение', {decisionBefore: 'REJECT', decisionAfter: 'APPROVE'}, 'rejectToApprove'],
        dd: ['Отказ → отказ', {decisionBefore: 'REJECT', decisionAfter: 'REJECT'}, 'rejectToReject']
    };
    const changes = {SAME: 'Совпало', VALUE_CHANGED: 'Значение изменилось',
        ATTRIBUTE_NEW: 'Атрибут появился', ATTRIBUTE_LOST: 'Атрибут пропал'};
    const HACKATHON_PRESET_NAME = 'Хакатон · базовый анализ';
    const CHECK_GROUP_PAGE_SIZE = 8;

    function plural(value, forms) {
        const count = Math.abs(value) % 100;
        const last = count % 10;
        if (count > 10 && count < 20) return `${number.format(value)} ${forms[2]}`;
        if (last > 1 && last < 5) return `${number.format(value)} ${forms[1]}`;
        return `${number.format(value)} ${last === 1 ? forms[0] : forms[2]}`;
    }

    let activeId;
    let activeReport;
    let view = 0;
    let listRequest = 0;
    let detailRequest = 0;
    let selectedRow;
    const checkTemplates = {
        'check_ar_drop_by_product.py': ['Падение доли одобрений', 'Сравнивает долю одобрений по продукту', 'max_drop_rel: 0.10'],
        'check_attribute_new_loss.py': ['Изменение состава атрибутов', 'Находит появившиеся и исчезнувшие поля', 'min_share: 0.001'],
        'check_external_score_drift.py': ['Дрейф внешнего скоринга', 'Сопоставляет изменения входов и оценок', 'Параметры в исходнике'],
        'check_stop_factor_disappeared.py': ['Пропавший стоп-фактор', 'Ищет исчезнувшие срабатывания', 'low_confidence_cnt: 5'],
        'check_target_model_changed.py': ['Смена целевой модели', 'Проверяет модель, выбранную для решения', 'low_confidence_cnt: 5']
    };
    const scriptEditors = {normalizer: '#bp-normalizer-code', procedure: '#bp-procedure-code',
        facts: '#bp-facts-code', check: '#bp-check-code'};
    const scriptSelections = {normalizer: '#bp-configuration-normalizer', procedure: '#bp-configuration-procedure',
        facts: '#bp-configuration-facts'};
    const bundledScripts = {normalizer: 'normalize_dataset.py', procedure: 'compare_dataset.py',
        facts: 'prepare_changed_facts.py'};
    const scriptBaselines = new Map();
    let scriptRequest = 0;
    let stageSourceRequest = 0;
    let journalSignature;
    let replayRequest;
    let workspaceReady;
    let savedConfigurations = [];
    let configurationChecks = [];
    let configurationProcedures = [];
    let configurationBusy = false;
    let configurationReady = false;
    let wizardStep = 0;
    let launchingView;
    let previewRequest = 0;
    let normalizationPreviewRequest = 0;
    const wizardSteps = ['Источник', 'Формат и предпросмотр', 'Приведение данных', 'Проверки', 'Порядок и запуск'];
    let pivotRequest = 0;
    let pivotCellRequest = 0;
    let pivotFieldRequest = 0;
    let pivotSegmentRequest = 0;
    const pivotLabels = {
        BEFORE: 'До', AFTER: 'После', '0': 'Без отличий', '1': 'С отличиями',
        'BEFORE / 0': 'До · без отличий', 'BEFORE / 1': 'До · с отличиями',
        'AFTER / 0': 'После · без отличий', 'AFTER / 1': 'После · с отличиями', ...changes
    };

    function ensureWorkspace() {
        if (!workspaceReady) {
            const initialize = async () => {
                const response = await fetch('/api/comparisons/workspace', {cache: 'no-store'});
                if (!response.ok) throw new Error('Рабочая область недоступна');
            };
            const request = navigator.locks ? navigator.locks.request('balance.workspace', initialize) : initialize();
            workspaceReady = request.catch(error => {
                workspaceReady = undefined;
                throw error;
            });
        }
        return workspaceReady;
    }

    async function api(path, options) {
        if (path.startsWith('/comparisons')) await ensureWorkspace();
        const response = await fetch('/api' + path, {cache: 'no-store', ...options});
        if (response.status === 428) workspaceReady = undefined;
        const body = await response.json().catch(() => null);
        if (!response.ok || body === null) {
            throw new Error(body?.message || 'Не удалось получить ответ сервера');
        }
        return body;
    }

    function node(tag, value, className) {
        const element = document.createElement(tag);
        element.textContent = value;
        if (className) element.className = className;
        return element;
    }

    function button(label, click, className = 'bp-button cursor-interaction') {
        const element = node('button', label, className);
        element.type = 'button';
        element.addEventListener('click', click);
        return element;
    }

    function status(message = '') {
        q('#bp-status').textContent = message;
        q('#bp-status').hidden = !message;
    }

    function navigate(section) {
        view += 1;
        q('#bp-title').textContent = titles[section];
        q('#bp-breadcrumb').textContent = titles[section];
        q('#bp-library-title').textContent = titles[section];
        q('#bp-create').hidden = true;
        q('#bp-report').hidden = true;
        q('#bp-check-dashboard').hidden = true;
        q('#bp-pivot').hidden = true;
        q('#bp-new').hidden = section === 'pivot';
        q('#bp-journal').hidden = section !== 'checks';
        q('#bp-stage-source').hidden = true;
        stageSourceRequest += 1;
        journalSignature = undefined;
        q('#bp-library').hidden = section === 'report' || section === 'pivot' || section === 'checks';
        all('[data-section]').forEach(item => {
            if (item.dataset.section === (section === 'pivot' ? 'report' : section)) item.setAttribute('aria-current', 'page');
            else item.removeAttribute('aria-current');
        });
        status();
        return view;
    }

    async function loadDatasetOptions(current) {
        const items = [];
        let page = 0;
        let totalPages;
        do {
            const result = await api('/datasets?size=100&page=' + page++);
            if (view !== current) return [];
            items.push(...result.items);
            totalPages = result.page.totalPages;
        } while (page < totalPages);
        return items;
    }

    function showWizardStep(step, focus = false) {
        wizardStep = step;
        q('.bp-configuration').hidden = step < 2;
        q('#bp-wizard-progress').textContent = `Шаг ${step + 1} из ${wizardSteps.length} · ${wizardSteps[step]}`;
        for (const page of all('[data-wizard-page]')) {
            page.hidden = Number(page.dataset.wizardPage) !== step;
        }
        const dataset = q('#bp-dataset');
        const selected = dataset.selectedOptions[0]?.textContent || '';
        q('#bp-wizard-source').textContent = selected;
        const count = all('#bp-configuration-checks [data-check-enabled]:checked').length
            + (q('#bp-check-code').value.trim() ? 1 : 0);
        q('#bp-wizard-summary').textContent = `${selected} · Проверок: ${number.format(count)}`;
        const last = step === wizardSteps.length - 1;
        const launching = launchingView === view;
        q('#bp-wizard-back').hidden = step === 0;
        q('#bp-wizard-back').disabled = launching || configurationBusy;
        q('#bp-wizard-next').hidden = last;
        q('#bp-wizard-next').disabled = !dataset.value;
        q('#bp-start').hidden = !last;
        q('#bp-start').disabled = !dataset.value || launching || configurationBusy;
        renderExpertStages();
        renderInlineSummary();
        if (focus) q(`[data-wizard-page="${step}"] h3`).focus();
        if (step === 1) loadWizardPreview(dataset.value);
        else previewRequest += 1;
    }

    async function loadWizardPreview(datasetId) {
        const request = ++previewRequest;
        const current = view;
        const message = q('#bp-wizard-preview-status');
        const preview = q('#bp-wizard-preview');
        preview.hidden = true;
        message.textContent = 'Загрузка примера пары…';
        try {
            const pair = await api('/comparisons/datasets/' + encodeURIComponent(datasetId) + '/preview');
            if (view !== current || request !== previewRequest) return;
            q('#bp-wizard-preview-id').textContent = pair.applicationId;
            q('#bp-wizard-before-label').textContent = 'До · ' + pair.beforeLabel;
            q('#bp-wizard-after-label').textContent = 'После · ' + pair.afterLabel;
            q('#bp-wizard-before').textContent = pair.decisionBefore === 'APPROVE' ? 'Одобрено' : 'Отказ';
            q('#bp-wizard-after').textContent = pair.decisionAfter === 'APPROVE' ? 'Одобрено' : 'Отказ';
            preview.hidden = false;
            message.textContent = '';
        } catch (error) {
            if (view === current && request === previewRequest) message.textContent = error.message;
        }
    }

    async function openForm() {
        updateRunLocation();
        const current = navigate('report');
        q('#bp-create').hidden = false;
        const select = q('#bp-dataset');
        select.replaceChildren(new Option('Загрузка наборов…', ''));
        configurationReady = false;
        savedConfigurations = [];
        configurationChecks = [];
        configurationProcedures = [];
        renderConfigurationOptions();
        renderConfiguration();
        renderConfigurationMode();
        showWizardStep(0);
        try {
            const items = await loadDatasetOptions(current);
            if (view !== current) return;
            select.replaceChildren(...items.map(item => new Option(
                item.name + ' · ' + number.format(item.applicationCount) + ' заявок', item.id)));
            if (items.length === 0) select.add(new Option('Нет зарегистрированных наборов', ''));
            if (items.length) await loadConfigurations(current);
            if (view === current) showWizardStep(wizardStep);
        } catch (error) {
            if (view === current) {
                select.replaceChildren(new Option('Каталог недоступен', ''));
                status(error.message);
            }
        }
    }

    async function loadConfigurations(current) {
        try {
            const [items, checks, procedures] = await Promise.all([
                api('/comparisons/configurations/v2'), api('/comparisons/configurations/checks'),
                api('/comparisons/configurations/procedures')
            ]);
            if (view !== current) return;
            savedConfigurations = items.map(configurationFields);
            configurationChecks = checks;
            configurationProcedures = procedures;
            configurationReady = true;
            renderConfigurationOptions();
            renderConfiguration();
        } catch (error) {
            if (view === current) q('#bp-configuration-status').textContent = error.message;
        }
    }

    function renderConfigurationOptions(selected = '') {
        q('#bp-configuration').replaceChildren(new Option(HACKATHON_PRESET_NAME, ''),
            ...savedConfigurations.map(item => new Option(item.name, item.id)));
        q('#bp-configuration').value = selected;
    }

    function renderPresetSummary() {
        const summary = q('#bp-preset-summary');
        const selectedPreset = !q('#bp-configuration').value;
        summary.hidden = !selectedPreset;
        if (!selectedPreset) return;
        const checks = readCheckPlan().filter(check => check.enabled).map(check =>
            check.script?.file || check.rule?.label || 'условие без названия');
        if (q('#bp-check-code').value.trim()) checks.push('дополнительная проверка из редактора');
        q('#bp-preset-script-list').textContent = checks.length
            ? `Проверки этого запуска (${checks.length}): ${checks.join(', ')}`
            : 'Проверки выключены. Сравнение и сводная будут доступны.';
    }

    function procedureFiles(stage) {
        return configurationProcedures.filter(file => file === bundledScripts[stage]
            || !Object.values(bundledScripts).includes(file));
    }

    function renderConfiguration() {
        q('.bp-configuration').disabled = !configurationReady || configurationBusy;
        const config = savedConfigurations.find(item => item.id === q('#bp-configuration').value);
        q('#bp-configuration-name').value = config?.name || '';
        clearNormalizationPreview();
        q('#bp-analysis-rows').replaceChildren();
        q('#bp-analysis-add').disabled = false;
        for (const field of config?.analysisFields || []) appendAnalysisField(field);
        q('#bp-analysis-editor').open = Boolean(config?.analysisFields?.length);
        q('#bp-normalization-rows').replaceChildren();
        q('#bp-normalization-add').disabled = false;
        for (const rule of config?.normalization || []) appendNormalizationRule(rule);
        q('#bp-normalization-editor').open = Boolean(config?.normalization?.length);
        if (config) q('#bp-dataset').value = config.datasetId;
        const procedures = q('#bp-configuration-procedure');
        const comparisonFiles = procedureFiles('procedure');
        procedures.replaceChildren(new Option('Штатная процедура', ''),
            ...comparisonFiles.map(name => new Option(name, name)));
        if (config?.procedure && !comparisonFiles.includes(config.procedure)) {
            procedures.add(new Option(config.procedure + ' · недоступен', config.procedure));
        }
        procedures.value = config?.procedure || '';
        procedures.closest('label').hidden = comparisonFiles.length === 0;
        const normalizers = q('#bp-configuration-normalizer');
        const normalizationFiles = procedureFiles('normalizer');
        normalizers.replaceChildren(new Option('В составе процедуры сравнения', ''),
            ...normalizationFiles.map(name => new Option(name, name)));
        if (config?.normalizer && !normalizationFiles.includes(config.normalizer)) {
            normalizers.add(new Option(config.normalizer + ' · недоступен', config.normalizer));
        }
        normalizers.value = config?.normalizer || '';
        normalizers.closest('label').hidden = normalizationFiles.length === 0;
        const facts = q('#bp-configuration-facts');
        const factsFiles = procedureFiles('facts');
        facts.replaceChildren(new Option('Все строки сравнения', ''),
            ...factsFiles.map(name => new Option(name, name)));
        if (config?.factsProcedure && !factsFiles.includes(config.factsProcedure)) {
            facts.add(new Option(config.factsProcedure + ' · недоступен', config.factsProcedure));
        }
        facts.value = config?.factsProcedure || '';
        facts.closest('label').hidden = factsFiles.length === 0;
        q('#bp-facts-code').value = config?.factsCode || '';
        q('#bp-normalizer-code').value = config?.normalizerCode || '';
        q('#bp-procedure-code').value = config?.procedureCode || '';
        q('#bp-check-code').value = config?.checkCode || '';
        q('#bp-configuration-parameters').value = config?.parameters ? JSON.stringify(config.parameters, null, 2) : '';
        q('#bp-configuration-checks').replaceChildren();
        if (configurationReady && !configurationChecks.length) {
            q('#bp-configuration-checks').append(node('p', 'Шаблоны проверок не найдены. Проверьте BALANCE_CHECKS_PATH на сервере.', 'bp-muted'));
        }
        for (const check of defaultCheckPlan(config)) appendCheckRow(check);
        q('#bp-save-configuration').disabled = !configurationReady || configurationBusy;
        q('#bp-copy-configuration').disabled = !configurationReady || configurationBusy || !config;
        q('#bp-configuration-status').textContent = '';
        scriptBaselines.clear();
        renderScriptCatalog();
        renderPresetSummary();
    }

    function renderScriptCatalog() {
        scriptRequest += 1;
        const stage = q('#bp-script-target').value;
        const files = stage === 'check' ? configurationChecks : procedureFiles(stage);
        q('#bp-script-file').replaceChildren(...files.map(file => new Option(file, file)));
        if (!files.length) q('#bp-script-file').add(new Option('Нет доступных файлов', ''));
        q('#bp-script-copy').disabled = !files.length;
        q('#bp-script-status').textContent = '';
        renderScriptDiff();
    }

    function renderScriptDiff() {
        const stage = q('#bp-script-target').value;
        const baseline = scriptBaselines.get(stage);
        q('#bp-script-diff').hidden = baseline === undefined;
        if (baseline === undefined) return;
        const before = baseline.split('\n');
        const after = q(scriptEditors[stage]).value.split('\n');
        let start = 0;
        while (start < before.length && start < after.length && before[start] === after[start]) start += 1;
        let end = 0;
        while (end < before.length - start && end < after.length - start
                && before[before.length - 1 - end] === after[after.length - 1 - end]) end += 1;
        const changes = [...before.slice(start, before.length - end).map(line => '- ' + line),
            ...after.slice(start, after.length - end).map(line => '+ ' + line)];
        q('#bp-script-diff-text').textContent = changes.length ? changes.join('\n') : 'Различий нет';
    }

    async function loadScriptSource() {
        const stage = q('#bp-script-target').value;
        const name = q('#bp-script-file').value;
        if (!name || configurationBusy) return;
        const editor = q(scriptEditors[stage]);
        const request = ++scriptRequest;
        const current = view;
        const params = new URLSearchParams({kind: stage === 'check' ? 'check' : 'procedure', name});
        const message = q('#bp-script-status');
        message.textContent = 'Загрузка исходника…';
        try {
            const source = await api('/comparisons/configurations/scripts/source?' + params);
            if (view !== current || request !== scriptRequest || configurationBusy) return;
            scriptBaselines.set(stage, source.code.replace(/\r\n?|\u000d/g, '\n'));
            if (!editor.value.trim()) {
                editor.value = source.code;
                if (stage === 'check') {
                    for (const check of all('#bp-configuration-checks [data-check-enabled]')) {
                        if (check.value === name) check.checked = false;
                    }
                } else {
                    q(scriptSelections[stage]).value = '';
                }
                clearNormalizationPreview();
                renderInlineSummary();
                message.textContent = 'Исходник скопирован в черновик. Сохраните конфигурацию после правок.';
            } else {
                message.textContent = 'Исходник загружен для сравнения. Код черновика сохранён.';
            }
            renderScriptDiff();
        } catch (error) {
            if (view === current && request === scriptRequest) message.textContent = error.message;
        }
    }

    async function validateScriptEditor() {
        const stage = q('#bp-script-target').value;
        const editor = q(scriptEditors[stage]);
        const code = editor.value;
        const message = q('#bp-script-status');
        const request = ++scriptRequest;
        const current = view;
        message.textContent = 'Проверка синтаксиса…';
        try {
            const result = await api('/comparisons/configurations/scripts/validate', {
                method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({code})
            });
            if (view !== current || request !== scriptRequest || code !== editor.value) return;
            if (!result.valid) {
                message.textContent = (result.line ? 'Строка ' + result.line + ': ' : '') + result.message;
                return;
            }
            message.textContent = stage === 'check' && !result.declaresCheck
                ? 'Синтаксис корректен. Объявление CHECK не найдено статически.'
                : 'Синтаксис корректен. Контракт результата проверяется при запуске.';
        } catch (error) {
            if (view === current && request === scriptRequest) message.textContent = error.message;
        }
    }

    function appendNormalizationRule(rule = {}) {
        const rows = q('#bp-normalization-rows');
        if (rows.children.length >= 100) return;
        const row = node('tr');
        const input = (name, parts, separator, placeholder, label) => {
            const item = node('input');
            item.dataset.rule = name;
            item.dataset.original = JSON.stringify(parts);
            item.defaultValue = parts.join(separator);
            item.placeholder = placeholder;
            item.setAttribute('aria-label', label);
            return item;
        };
        const kind = node('select');
        kind.dataset.rule = 'kind';
        kind.setAttribute('aria-label', 'Действие или тип');
        for (const [value, title] of [['keep', 'Без приведения'], ['string', 'Строка'], ['number', 'Число'],
            ['boolean', 'Логическое'], ['exclude', 'Исключить'], ['keys', 'Ключ массива']]) {
            kind.add(new Option(title, value));
        }
        kind.value = rule.excluded ? 'exclude' : rule.arrayKeys?.length ? 'keys' : rule.type || 'keep';
        const fields = [
            input('source', rule.sourcePath || [], '.', 'appData.application_id', 'Исходный путь'),
            input('target', rule.targetPath || [], '.', 'Тот же путь', 'Целевой путь'),
            kind,
            input('keys', rule.arrayKeys || [], ', ', 'product_id, type', 'Ключи массива'),
            button('Удалить правило', () => {
                row.remove();
                clearNormalizationPreview();
                q('#bp-normalization-add').disabled = false;
            })
        ];
        for (const field of fields) {
            const cell = node('td');
            cell.append(field);
            row.append(cell);
        }
        rows.append(row);
        q('#bp-normalization-add').disabled = rows.children.length >= 100;
    }

    function readNormalizationRules() {
        return [...all('#bp-normalization-rows tr')].map((row, index) => {
            const field = name => row.querySelector(`[data-rule="${name}"]`);
            const parts = (name, separator) => {
                const input = field(name);
                if (input.value === input.defaultValue) return JSON.parse(input.dataset.original);
                return input.value.trim() ? input.value.split(separator).map(part => part.trim()) : [];
            };
            const sourcePath = parts('source', '.');
            const targetPath = parts('target', '.');
            const kind = field('kind').value;
            const arrayKeys = parts('keys', ',');
            if (!sourcePath.length || [...sourcePath, ...targetPath, ...arrayKeys].includes('')
                    || (kind === 'keys') !== Boolean(arrayKeys.length)
                    || ((kind === 'exclude' || kind === 'keys') && targetPath.length)) {
                throw new Error(`Правило ${index + 1}: проверьте пути, действие и ключи массива.`);
            }
            return {sourcePath, targetPath, type: kind === 'exclude' || kind === 'keys' ? 'keep' : kind,
                excluded: kind === 'exclude', arrayKeys};
        });
    }

    function clearNormalizationPreview() {
        normalizationPreviewRequest += 1;
        q('#bp-normalization-preview').hidden = true;
        q('#bp-normalization-preview-status').textContent = '';
        q('#bp-normalization-preview-button').disabled = false;
        q('#bp-analysis-preview-button').disabled = false;
    }

    async function showNormalizationPreview(analysis = false) {
        clearNormalizationPreview();
        const request = normalizationPreviewRequest;
        const current = view;
        const message = q('#bp-normalization-preview-status');
        const preview = q('#bp-normalization-preview');
        const button = q(analysis ? '#bp-analysis-preview-button' : '#bp-normalization-preview-button');
        try {
            const input = configurationInput();
            if (analysis && (input.normalizer || input.normalizerCode)) {
                throw new Error('Предпросмотр группировки доступен только со штатным приведением. Отдельный нормализатор применяется в полном запуске.');
            }
            if ((analysis || input.normalization.length) && (input.procedure || input.procedureCode
                    || input.normalizer || input.normalizerCode)) {
                throw new Error('Предпросмотр табличных настроек требует штатного сравнения.');
            }
            button.disabled = true;
            message.textContent = 'Применяем правила к примеру пары…';
            const previewPath = analysis ? '/analysis-preview' : '/normalization-preview';
            const body = analysis ? {normalization: input.normalization, analysisFields: input.analysisFields} : input.normalization;
            const pair = await api('/comparisons/datasets/' + encodeURIComponent(input.datasetId) + previewPath, {
                method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
            });
            if (view !== current || request !== normalizationPreviewRequest) return;
            if (pair.error) throw new Error(pair.error);
            const rows = [];
            for (const [label, values] of [['До', pair.before], ['После', pair.after]]) {
                for (const value of values) {
                    const row = node('tr');
                    const field = value.label ? value.label + ' · ' + value.path : value.path;
                    row.append(...[label, field, value.key, value.value, analysis ? value.group : '—']
                        .map(text => node('td', text)));
                    rows.push(row);
                }
            }
            q('#bp-normalization-preview-rows').replaceChildren(...rows);
            preview.hidden = !rows.length;
            message.textContent = rows.length ? 'До 25 строк на срез. Сначала показаны поля из правил.' : 'В примере нет строк.';
        } catch (error) {
            if (view === current && request === normalizationPreviewRequest) message.textContent = error.message;
        } finally {
            if (view === current && request === normalizationPreviewRequest) button.disabled = false;
        }
    }

    function appendAnalysisField(field = {}) {
        const rows = q('#bp-analysis-rows');
        if (rows.children.length >= 100) return;
        const row = node('tr');
        const editor = (tag, label, value, original) => {
            const input = node(tag);
            input.setAttribute('aria-label', label);
            input.defaultValue = value;
            if (original) input.dataset.original = JSON.stringify(original);
            return input;
        };
        const path = field.path || [];
        const buckets = field.buckets || [];
        const fields = [
            editor('input', 'Путь поля анализа', path.join('.'), path),
            editor('input', 'Подпись поля анализа', field.label || ''),
            editor('textarea', 'Интервалы поля анализа', buckets.map(bucket =>
                (bucket.upper ?? '*') + ' = ' + bucket.label).join('\n'), buckets),
            button('Удалить поле', () => {
                row.remove();
                clearNormalizationPreview();
                q('#bp-analysis-add').disabled = false;
            })
        ];
        for (const input of fields) {
            const cell = node('td');
            cell.append(input);
            row.append(cell);
        }
        rows.append(row);
        q('#bp-analysis-add').disabled = rows.children.length >= 100;
    }

    function readAnalysisFields() {
        return [...all('#bp-analysis-rows tr')].map((row, index) => {
            const [pathInput, labelInput, bucketInput] = row.querySelectorAll('input, textarea');
            const path = pathInput.value === pathInput.defaultValue ? JSON.parse(pathInput.dataset.original)
                : pathInput.value.split('.').map(part => part.trim());
            const label = labelInput.value === labelInput.defaultValue ? labelInput.defaultValue : labelInput.value.trim();
            if (!path.length || path.includes('') || !label.trim()) {
                throw new Error(`Поле анализа ${index + 1}: укажите путь и подпись.`);
            }
            const buckets = bucketInput.value === bucketInput.defaultValue ? JSON.parse(bucketInput.dataset.original)
                : bucketInput.value.split('\n').filter(line => line.trim()).map(line => {
                    const separator = line.indexOf('=');
                    const bound = line.slice(0, separator).trim();
                    const bucketLabel = line.slice(separator + 1).trim();
                    const upper = bound === '*' ? null : bound;
                    if (separator < 1 || !bucketLabel || !bound || (upper !== null && !/^-?[0-9]+(?:\.[0-9]+)?$/.test(upper))) {
                        throw new Error(`Поле анализа ${index + 1}: укажите границу и подпись интервала.`);
                    }
                    return {upper, label: bucketLabel};
                });
            return {path, label, role: 'group', buckets};
        });
    }

    function defaultCheckPlan(config) {
        const existing = config?.checkPlan || [];
        const known = new Set(existing.map(check => check.script?.file).filter(Boolean));
        const available = configurationChecks.filter(file => !known.has(file));
        const added = config ? available.slice(0, Math.max(0, 40 - existing.length)) : available;
        return [...existing, ...added.map(file => ({id: 'file:' + file, enabled: !config, script: {kind: 'catalog', file}}))];
    }

    function appendCheckRow(check) {
        const group = q('#bp-configuration-checks');
        const row = node('section', '', 'bp-wizard-page');
        row.dataset.checkId = check.id;
        const enabled = node('input');
        enabled.type = 'checkbox';
        enabled.checked = check.enabled;
        enabled.dataset.checkEnabled = '';
        const label = node('label', '', 'bp-check-option');
        if (check.rule) {
            const rule = check.rule;
            label.append(enabled, document.createTextNode('Включить пользовательскую проверку'));
            row.append(label);
            const add = (caption, control, key) => {
                control.dataset.ruleField = key;
                const wrapper = node('label', caption);
                wrapper.append(control);
                row.append(wrapper);
            };
            const text = (caption, key) => {
                const field = node('input');
                field.value = rule[key] || '';
                add(caption, field, key);
                return field;
            };
            text('Название проверки', 'label');
            text('Путь атрибута', 'path');
            const type = node('select');
            for (const [value, title] of [['threshold', 'Превышение порога'], ['range', 'Выход из диапазона'],
                ['share', 'Доля заявок с атрибутом'], ['change', 'Изменение между срезами']]) {
                type.add(new Option(title, value));
            }
            type.value = rule.type;
            add('Тип условия', type, 'type');
            const source = node('select');
            source.add(new Option('До', 'BEFORE'));
            source.add(new Option('После', 'AFTER'));
            source.value = rule.source;
            add('Срез проверки', source, 'source');
            const min = text('Нижняя граница', 'min');
            const max = text('Верхняя граница или доля от 0 до 1', 'max');
            const refresh = () => {
                source.disabled = type.value === 'change';
                if (source.disabled) source.value = 'AFTER';
                min.parentElement.hidden = type.value !== 'range';
                max.parentElement.hidden = type.value === 'change';
            };
            type.addEventListener('change', refresh);
            refresh();
            row.append(button('Удалить условие', () => {
                row.remove();
                renderPresetSummary();
            }));
        } else {
            const file = check.script.file;
            const metadata = checkTemplates[file] || [file, 'Проверка из каталога', 'Параметры в исходнике'];
            enabled.value = file;
            label.append(enabled, document.createTextNode(metadata[0]));
            row.append(label, node('p', metadata[1] + ' · ' + metadata[2], 'bp-muted'));
        }
        const controls = node('div', '', 'bp-configuration-actions');
        controls.append(button('Выше', () => {
            if (row.previousElementSibling) group.insertBefore(row, row.previousElementSibling);
        }), button('Ниже', () => {
            if (row.nextElementSibling) group.insertBefore(row.nextElementSibling, row);
        }));
        row.append(controls);
        group.append(row);
    }

    function readCheckPlan() {
        return [...q('#bp-configuration-checks').children].map(row => {
            const enabled = row.querySelector('[data-check-enabled]');
            const base = {id: row.dataset.checkId, enabled: enabled.checked};
            if (!base.id.startsWith('rule:')) return {...base, script: {kind: 'catalog', file: enabled.value}};
            const get = name => row.querySelector(`[data-rule-field="${name}"]`).value;
            const type = get('type');
            const rule = {type, label: get('label'), path: get('path'), source: get('source')};
            if (type === 'range') rule.min = get('min').trim();
            if (type !== 'change') rule.max = get('max').trim();
            return {...base, rule};
        });
    }

    function sameCheckPlan(left, right) {
        const canonical = checks => checks.map(check => check.rule
            ? [check.id, check.enabled, check.rule.type, check.rule.label, check.rule.path,
                check.rule.source, check.rule.min, check.rule.max]
            : [check.id, check.enabled, check.script.file]);
        return JSON.stringify(canonical(left)) === JSON.stringify(canonical(right));
    }

    function configurationInput() {
        const text = q('#bp-configuration-parameters').value.trim();
        let parameters;
        try {
            parameters = text ? JSON.parse(text) : null;
        } catch {
            throw new Error('Введите корректный JSON параметров');
        }
        return {name: q('#bp-configuration-name').value, datasetId: q('#bp-dataset').value,
            checkPlan: readCheckPlan(),
            procedure: q('#bp-configuration-procedure').value || null,
            normalizer: q('#bp-configuration-normalizer').value || null,
            factsProcedure: q('#bp-configuration-facts').value || null,
            factsCode: q('#bp-facts-code').value.trim() ? q('#bp-facts-code').value : null,
            normalizerCode: q('#bp-normalizer-code').value.trim() ? q('#bp-normalizer-code').value : null,
            procedureCode: q('#bp-procedure-code').value.trim() ? q('#bp-procedure-code').value : null,
            checkCode: q('#bp-check-code').value.trim() ? q('#bp-check-code').value : null,
            factsFirst: false, normalization: readNormalizationRules(), analysisFields: readAnalysisFields(), parameters};
    }

    function configurationFields(document) {
        const script = type => document.stages.find(stage => stage.type === type)?.script;
        return {id: document.id, name: document.name, datasetId: document.source.datasetId,
            checkPlan: document.checks.filter(check => check.script?.kind !== 'inline'),
            checkCode: document.checks.find(check => check.enabled && check.script?.kind === 'inline')?.script.code,
            procedure: script('COMPARISON')?.file, procedureCode: script('COMPARISON')?.code,
            normalizer: script('NORMALIZATION')?.file, normalizerCode: script('NORMALIZATION')?.code,
            factsProcedure: script('FACTS')?.file, factsCode: script('FACTS')?.code,
            normalization: document.normalization, analysisFields: document.analysisFields, factsFirst: document.stages[2]?.type === 'FACTS', parameters: document.legacyParameters};
    }

    function configurationDocument(input) {
        const script = (file, code, label) => {
            if (file && code) {
                throw new Error(label + ': выбран файл и задан свой Python. В экспертном режиме очистите код или снимите выбор файла.');
            }
            return file ? {kind: 'catalog', file} : code ? {kind: 'inline', code} : {kind: 'default'};
        };
        const stage = (id, type, enabled, selection = script()) => ({id, type, enabled, script: selection});
        const required = input.checkPlan.filter(check => check.enabled || check.rule).length;
        if (required > 40) throw new Error('В сохранённом плане допускается до 40 шаблонов и правил. Отключите лишние шаблоны.');
        let disabledSlots = 40 - required;
        const checks = input.checkPlan.filter(check => check.enabled || check.rule || disabledSlots-- > 0);
        if (input.checkCode) checks.push({id: 'editor', enabled: true, script: script(null, input.checkCode)});
        const check = stage('checks', 'CHECKS', checks.some(item => item.enabled));
        const facts = stage('facts', 'FACTS', Boolean(input.factsProcedure || input.factsCode),
            script(input.factsProcedure, input.factsCode, 'Подготовка фактов'));
        return {schemaVersion: 2, name: input.name,
            source: {kind: 'localPackage', datasetId: input.datasetId, format: 'sprJsonPair'},
            normalization: input.normalization, analysisFields: input.analysisFields,
            stages: [stage('normalize', 'NORMALIZATION', Boolean(input.normalizer || input.normalizerCode),
                script(input.normalizer, input.normalizerCode, 'Нормализация')),
                stage('compare', 'COMPARISON', true, script(input.procedure, input.procedureCode, 'Сравнение')),
                ...(input.factsFirst ? [facts, check] : [check, facts]), stage('publish', 'IMPORT', true)],
            checks, legacyParameters: input.parameters};
    }

    /** Создаёт снимок разового запуска с именем выбранной или штатной конфигурации. */
    function runConfigurationDraft(selectedConfiguration) {
        const input = configurationInput();
        input.name = input.name.trim() || (selectedConfiguration
            ? savedConfigurations.find(item => item.id === selectedConfiguration)?.name || 'Разовый запуск'
            : HACKATHON_PRESET_NAME);
        return configurationDocument(input);
    }

    function renderConfigurationMode() {
        for (const item of all('[data-expert-only]')) item.hidden = false;
    }

    function renderInlineSummary() {
        const stages = [['#bp-normalizer-code', 'нормализация'], ['#bp-procedure-code', 'сравнение'],
            ['#bp-facts-code', 'подготовка фактов'], ['#bp-check-code', 'дополнительная проверка']];
        const names = stages.filter(([selector]) => q(selector).value.trim()).map(([, name]) => name);
        const message = q('#bp-inline-summary');
        message.hidden = names.length === 0;
        message.textContent = 'Свой Python: ' + names.join(', ') + '. Настройки кода доступны ниже.';
    }

    function renderExpertStages() {
        const factsFirst = false;
        const normalize = q('#bp-configuration-normalizer').value || q('#bp-normalizer-code').value.trim();
        const facts = q('#bp-configuration-facts').value || q('#bp-facts-code').value.trim();
        const checks = all('#bp-configuration-checks [data-check-enabled]:checked').length || q('#bp-check-code').value.trim();
        const labels = {NORMALIZATION: normalize ? 'Нормализация' : 'Нормализация в составе сравнения',
            COMPARISON: 'Сравнение', FACTS: facts ? 'Подготовка фактов' : 'Штатные факты при публикации',
            CHECKS: checks ? 'Проверки' : 'Проверки пропущены', IMPORT: 'Публикация'};
        const types = ['NORMALIZATION', 'COMPARISON', ...(factsFirst ? ['FACTS', 'CHECKS'] : ['CHECKS', 'FACTS']), 'IMPORT'];
        q('#bp-expert-stages').replaceChildren(...types.map(type => node('li', labels[type])));
    }

    function hasUnsavedConfiguration() {
        const config = savedConfigurations.find(item => item.id === q('#bp-configuration').value);
        const input = configurationInput();
        if (!config) {
            return Boolean(input.name.trim() || input.procedure || input.normalizer || input.factsProcedure
                || input.procedureCode || input.normalizerCode || input.factsCode || input.checkCode
                || input.factsFirst || input.normalization.length || input.analysisFields.length || input.parameters !== null || !sameCheckPlan(input.checkPlan, defaultCheckPlan()));
        }
        return config.name !== input.name.trim() || config.datasetId !== input.datasetId || config.factsFirst !== input.factsFirst
            || JSON.stringify(config.normalization || []) !== JSON.stringify(input.normalization)
            || JSON.stringify(config.analysisFields || []) !== JSON.stringify(input.analysisFields)
            || (config.procedure || null) !== input.procedure || (config.normalizer || null) !== input.normalizer
            || (config.factsProcedure || null) !== input.factsProcedure || (config.factsCode || null) !== input.factsCode
            || (config.normalizerCode || null) !== input.normalizerCode
            || JSON.stringify(config.parameters || null) !== JSON.stringify(input.parameters)
            || (config.procedureCode || null) !== input.procedureCode || (config.checkCode || null) !== input.checkCode
            || !sameCheckPlan(input.checkPlan, defaultCheckPlan(config));
    }

    async function saveConfiguration(copy) {
        if (configurationBusy) return;
        const current = view;
        const id = copy ? '' : q('#bp-configuration').value;
        const message = q('#bp-configuration-status');
        let body;
        try {
            body = configurationDocument(configurationInput());
        } catch (error) {
            message.textContent = error.message;
            return;
        }
        configurationBusy = true;
        q('.bp-configuration').disabled = true;
        showWizardStep(wizardStep);
        q('#bp-save-configuration').disabled = true;
        q('#bp-copy-configuration').disabled = true;
        try {
            const document = await api('/comparisons/configurations' + (id ? '/' + id : '') + '/v2', {
                method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
            });
            if (view !== current) return;
            const item = configurationFields(document);
            savedConfigurations = [item, ...savedConfigurations.filter(config => config.id !== item.id)];
            renderConfigurationOptions(item.id);
            renderConfiguration();
            showWizardStep(wizardStep);
            message.textContent = 'Конфигурация сохранена';
        } catch (error) {
            if (view === current) message.textContent = error.message;
        } finally {
            configurationBusy = false;
            if (!q('#bp-create').hidden) {
                q('.bp-configuration').disabled = !configurationReady;
                showWizardStep(wizardStep);
                q('#bp-save-configuration').disabled = !configurationReady;
                q('#bp-copy-configuration').disabled = !configurationReady || !q('#bp-configuration').value;
            }
        }
    }

    async function startComparison(event) {
        event.preventDefault();
        if (wizardStep !== wizardSteps.length - 1 || q('#bp-start').disabled) return;
        let runDraft = false;
        const selectedConfiguration = q('#bp-configuration').value;
        try {
            if (configurationBusy) {
                q('#bp-configuration-status').textContent = 'Дождитесь сохранения конфигурации';
                return;
            }
            if (!selectedConfiguration && !readCheckPlan().some(check => check.enabled || check.rule)
                    && !q('#bp-check-code').value.trim()) {
                q('#bp-configuration-status').textContent = 'Штатная конфигурация требует хотя бы одну включённую проверку.';
                return;
            }
            const changed = hasUnsavedConfiguration();
            if (!selectedConfiguration || changed) {
                runDraft = true;
            }
            if (selectedConfiguration && changed) {
                runDraft = window.confirm('Запустить эти настройки один раз без сохранения? Нажмите «Отмена», чтобы вернуться и сохранить конфигурацию.');
                if (!runDraft) {
                    q('#bp-configuration-status').textContent = 'Черновик не запущен. Сохраните изменения кнопкой ниже или подтвердите разовый запуск.';
                    return;
                }
            } else if (!selectedConfiguration && changed) {
                const confirmed = window.confirm('Запустить изменённую штатную конфигурацию один раз без сохранения?');
                if (!confirmed) {
                    q('#bp-configuration-status').textContent = 'Разовый запуск отменён. Изменения можно сохранить как отдельную конфигурацию.';
                    return;
                }
            }
        } catch (error) {
            q('#bp-configuration-status').textContent = error.message;
            return;
        }
        const current = view;
        launchingView = current;
        showWizardStep(wizardStep);
        status('Постановка сравнения в очередь…');
        try {
            const run = await api('/comparisons', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    datasetId: q('#bp-dataset').value,
                    ...(runDraft ? {draft: runConfigurationDraft(selectedConfiguration)}
                        : {configurationId: selectedConfiguration})
                })
            });
            if (view === current) await openRun(run.id, run);
        } catch (error) {
            if (view === current) {
                status(error.message);
            }
        } finally {
            if (launchingView === current) launchingView = undefined;
            if (view === current) showWizardStep(wizardStep);
        }
    }

    function isPivotHash(hash) {
        return hash === '#bp-pivot' || hash === '#bp-pivot-prototype';
    }

    function updateRunLocation(id, applicationId, keepPivot = false) {
        const url = new URL(location.href);
        url.searchParams.delete('run');
        url.searchParams.delete('application');
        if (!keepPivot && isPivotHash(url.hash)) url.hash = '';
        if (id) url.searchParams.set('run', id);
        if (id && applicationId) url.searchParams.set('application', applicationId);
        if (url.href !== location.href) history.replaceState(history.state, '', url);
    }

    function renderJournal(run) {
        const panel = q('#bp-journal');
        panel.hidden = !run.journal?.length;
        if (panel.hidden) return;
        if (panel.dataset.runId !== run.id) {
            panel.open = false;
            panel.dataset.runId = run.id;
            journalSignature = undefined;
        }
        const details = [];
        if (run.input?.sourceRunId) details.push('Источник: ' + run.input.sourceRunId);
        if (run.configuration?.name) details.push('Конфигурация: ' + run.configuration.name);
        if (run.input?.schemaVersion) details.push('Схема v' + run.input.schemaVersion);
        if (run.input?.sha256) details.push('SHA-256 входа: ' + run.input.sha256);
        if (run.input?.runtimeSha256) details.push('SHA-256 Python: ' + run.input.runtimeSha256);
        if (run.input?.expectedSha256 && run.input.expectedSha256 !== run.input.sha256) {
            details.push('Ожидался вход: ' + run.input.expectedSha256);
        }
        if (run.input?.expectedRuntimeSha256 && run.input.expectedRuntimeSha256 !== run.input.runtimeSha256) {
            details.push('Ожидался Python: ' + run.input.expectedRuntimeSha256);
        }
        q('#bp-journal-input').textContent = details.join(' · ');
        const stageCounts = Object.fromEntries(Object.keys(stageStatuses).map(key => [key, 0]));
        for (const stage of run.journal) stageCounts[stage.status] += 1;
        const stageSummary = Object.entries(stageStatuses)
            .filter(([key]) => stageCounts[key] > 0)
            .map(([key, label]) => `${label}: ${number.format(stageCounts[key])}`);
        q('#bp-journal-summary').textContent = [run.input?.sourceRunId,
            run.configuration?.name, statuses[run.status] || run.status, ...stageSummary].filter(Boolean).join(' · ');
        const replay = q('#bp-replay');
        replay.hidden = run.status !== 'COMPLETED' || !run.input?.sha256 || !run.input?.runtimeSha256
            || !run.input?.schemaVersion;
        replay.disabled = replayRequest === run.id;
        const signature = JSON.stringify(run.journal);
        if (signature === journalSignature) return;
        journalSignature = signature;
        q('#bp-journal-stages').replaceChildren(...run.journal.map(stage => {
            const item = node('li', '', 'bp-journal-stage');
            item.dataset.status = stage.status;
            const state = node('span', stageStatuses[stage.status], 'bp-tag');
            item.append(node('strong', stageLabels[stage.name]), state);
            if (stage.startedAt) {
                const time = node('time', date.format(new Date(stage.startedAt)), 'bp-muted');
                time.dateTime = stage.startedAt;
                item.append(time);
                if (stage.finishedAt) {
                    const finished = node('time', 'Завершён: ' + date.format(new Date(stage.finishedAt)), 'bp-muted');
                    finished.dateTime = stage.finishedAt;
                    item.append(finished);
                }
                if (['NORMALIZATION', 'COMPARISON', 'CHECKS', 'FACTS'].includes(stage.name)) {
                    item.append(button('Код этапа', () => openStageSource(run.id, stage.name),
                        'bp-open cursor-interaction'));
                }
            }
            return item;
        }));
    }

    async function replayFromJournal() {
        const id = activeId;
        if (!id || replayRequest === id) return;
        const current = view;
        const control = q('#bp-replay');
        replayRequest = id;
        control.disabled = true;
        try {
            const run = await api('/comparisons/' + id + '/replay', {method: 'POST'});
            if (view === current) await openRun(run.id, run);
        } catch (error) {
            if (view === current) status(error.message);
        } finally {
            if (replayRequest === id) replayRequest = undefined;
            if (view === current) control.disabled = false;
        }
    }

    async function openStageSource(id, stage) {
        const request = ++stageSourceRequest;
        const content = q('#bp-stage-source-files');
        const heading = q('#bp-stage-source-title');
        q('#bp-stage-source').hidden = false;
        heading.textContent = 'Код этапа · ' + stageLabels[stage];
        heading.focus();
        content.textContent = 'Загрузка…';
        try {
            const [files, parameters] = await Promise.all([
                api('/comparisons/' + id + '/journal/' + stage + '/source'),
                api('/comparisons/' + id + '/journal/parameters')
            ]);
            if (request !== stageSourceRequest) return;
            content.replaceChildren(...files.map(file => {
                const details = document.createElement('details');
                details.append(node('summary', file.name), node('pre', file.content));
                return details;
            }));
            if (content.firstElementChild) content.firstElementChild.open = true;
            const settings = node('details');
            settings.append(node('summary', 'Параметры снимка'), node('pre', JSON.stringify(parameters, null, 2)));
            content.append(settings);
        } catch (error) {
            if (request === stageSourceRequest) content.textContent = error.message;
        }
    }

    async function openRun(id, initial, applicationId, keepPivot = false) {
        activeId = id;
        updateRunLocation(id, applicationId, keepPivot);
        const current = navigate('report');
        status('Загрузка статуса…');
        try {
            let run = initial;
            while (view === current) {
                run ??= await api('/comparisons/' + id);
                if (view !== current) return;
                renderJournal(run);
                if (run.status === 'FAILED') {
                    status('Ошибка · ' + run.error.message);
                    return;
                }
                if (run.status === 'COMPLETED') {
                    const report = await api('/comparisons/' + id + '/report');
                    if (view === current) {
                        if (report.pivotAvailable && isPivotHash(location.hash) && !applicationId) {
                            showPivot(report);
                        } else {
                            activeReport = report;
                            if (report.checksAvailable && !applicationId) {
                                await renderChecksDashboard(report);
                            } else {
                                renderReport(report);
                                if (applicationId) await openDetail(applicationId);
                            }
                        }
                    }
                    return;
                }
                status(statuses[run.status] + ' · ' + id);
                await new Promise(resolve => setTimeout(resolve, 1000));
                run = null;
            }
        } catch (error) {
            if (view === current) status(error.message);
        }
    }

    function renderReport(report) {
        activeReport = report;
        status();
        q('#bp-run-id').textContent = report.comparisonId;
        const checks = '/api/comparisons/' + report.comparisonId + '/checks.html';
        q('#bp-check-view').href = checks;
        q('#bp-check-links').hidden = !report.checksAvailable;
        q('#bp-total').textContent = number.format(report.totalApplications);
        q('#bp-diff').textContent = number.format(report.applicationsWithDifferences);
        q('#bp-changed').textContent = number.format(report.applicationsWithDecisionChanges);
        const matrix = report.transitionMatrix;
        all('[data-group]').forEach(item => {
            const key = groups[item.dataset.group][2];
            if (key) item.querySelector('strong').textContent = number.format(matrix[key]);
            item.setAttribute('aria-pressed', 'false');
        });
        const before = approval('before', matrix.approveToApprove + matrix.approveToReject, report.totalApplications);
        const after = approval('after', matrix.approveToApprove + matrix.rejectToApprove, report.totalApplications);
        q('#bp-approve-delta').textContent = ratio.format(after - before) + ' п.п.';
        q('#bp-list').hidden = true;
        q('#bp-detail').hidden = true;
        q('#bp-report').hidden = false;
        const pivotLink = q('#bp-open-pivot');
        pivotLink.hidden = !report.pivotAvailable;
        const runUrl = '/?' + new URLSearchParams({run: report.comparisonId});
        pivotLink.href = runUrl + '#bp-pivot';
        const prototypeLink = q('#bp-open-prototype');
        prototypeLink.hidden = !report.pivotAvailable;
        prototypeLink.href = runUrl + '#bp-pivot-prototype';
    }

    /** Показывает критические проверки и фактические строки сохранённого отчёта. */
    async function renderChecksDashboard(report) {
        const current = navigate('checks');
        q('#bp-check-dashboard').hidden = false;
        q('#bp-check-run-id').textContent = report.comparisonId;
        q('#bp-check-dashboard-pivot').hidden = !report.pivotAvailable;
        const checksReport = q('#bp-check-dashboard-report');
        checksReport.href = '/api/comparisons/' + report.comparisonId + '/checks.html';
        checksReport.hidden = !report.checksAvailable;
        q('#bp-check-signals').replaceChildren();
        q('#bp-check-states').replaceChildren();
        q('#bp-check-log-summary').textContent = '';
        const log = q('#bp-check-execution-log');
        log.replaceChildren();
        log.closest('details').open = false;
        q('#bp-check-summary').textContent = 'Загрузка результатов проверок…';
        try {
            const result = await api('/comparisons/' + report.comparisonId + '/checks');
            if (view !== current) return;
            const rows = Array.isArray(result.rows) ? result.rows : [];
            const checks = Array.isArray(result.checks) ? result.checks : [];
            const skipped = Array.isArray(result.skipped) ? result.skipped : [];
            const passed = rows.filter(row => row.RESULT === 'PASS').length;
            const failed = rows.filter(row => row.RESULT === 'FAIL').length;
            const signaled = rows.filter(row => row.RESULT === 'SIGNAL').length;
            const errors = checks.filter(check => check.status === 'ERROR').length;
            const noData = checks.filter(check => check.status === 'NO_DATA').length
                + rows.filter(row => row.RESULT === 'NO_DATA').length;
            const findingGroups = renderCheckFindings(rows, report.comparisonId);
            const summary = q('#bp-check-summary');
            const execution = node('section', '', 'bp-check-summary-card');
            execution.dataset.state = errors ? 'error' : noData ? 'signals' : checks.length ? 'ok' : 'empty';
            execution.append(node('strong', 'Исполнение'));
            const executionCounts = [
                plural(checks.length, ['проверка в журнале', 'проверки в журнале', 'проверок в журнале']),
                plural(errors, ['ошибка исполнения', 'ошибки исполнения', 'ошибок исполнения']),
                plural(noData, ['проверка без данных', 'проверки без данных', 'проверок без данных']),
                plural(skipped.length, ['проверка отключена', 'проверки отключены', 'проверок отключено'])
            ];
            execution.append(node('p', executionCounts.join(' · ')));
            execution.append(node('small', errors ? 'Есть проверки, которые не удалось выполнить.'
                : checks.length ? 'Проверки завершились без ошибок исполнения.'
                    : 'В этом запуске нет выполненных проверок.'));
            const results = node('section', '', 'bp-check-summary-card');
            results.dataset.state = failed ? 'error' : signaled ? 'signals' : passed ? 'ok' : 'empty';
            results.append(node('strong', 'Результаты проверок'));
            const criteriaRows = passed + failed;
            const passRate = criteriaRows ? percentage.format(passed / criteriaRows) : '—';
            const resultCounts = [
                plural(rows.length, ['строка результата', 'строки результата', 'строк результата']),
                plural(passed, ['строка PASS', 'строки PASS', 'строк PASS']),
                plural(failed, ['строка FAIL', 'строки FAIL', 'строк FAIL']),
                plural(signaled, ['сигнал', 'сигнала', 'сигналов']),
                plural(findingGroups, ['группа результатов', 'группы результатов', 'групп результатов']),
                `доля PASS среди PASS/FAIL: ${passRate}`
            ];
            results.append(node('p', resultCounts.join(' · ')));
            results.append(node('small', 'SIGNAL отражает обнаруженное изменение и не входит в расчёт доли PASS.'));
            summary.replaceChildren(execution, results);
            renderCheckExecutionLog(checks, skipped);
            const states = [
                ...checks.filter(check => check.status === 'ERROR').map(check => ({...check, statusLabel: 'Ошибка исполнения'})),
                ...checks.filter(check => check.status === 'NO_DATA').map(check => ({...check, statusLabel: 'Нет данных'})),
                ...skipped.map(check => ({...check, statusLabel: 'Отключена настройкой'}))
            ];
            for (const check of states) {
                const line = node('div', '', 'bp-check-state');
                line.append(node('strong', check.label || check.id || ''));
                line.append(node('span', check.statusLabel, 'bp-check-badge bp-check-other'));
                if (check.message) line.append(node('span', check.message, 'bp-muted'));
                q('#bp-check-states').append(line);
            }
        } catch (error) {
            if (view === current) q('#bp-check-summary').textContent = error.message;
        }
    }

    /** Группирует строки с нарушениями критериев и обнаруженными сигналами. */
    function renderCheckFindings(rows, comparisonId) {
        const container = q('#bp-check-signals');
        container.replaceChildren();
        const findings = rows.filter(row => row.RESULT === 'FAIL' || row.RESULT === 'SIGNAL');
        if (!findings.length) {
            const message = rows.some(row => row.RESULT === 'PASS')
                ? 'В полученных результатах нет нарушений и сигналов. Состояния остальных проверок показаны в журнале.'
                : 'Нет результатов с вердиктом или сигналом. Состояния проверок показаны в журнале.';
            container.append(node('p', message, 'bp-muted'));
            return 0;
        }
        const groups = new Map();
        for (const item of findings) {
            const key = JSON.stringify([item.RESULT, item.LABEL || 'Проверка', item.SOURCE || '', item.CONFIDENCE || '']);
            if (!groups.has(key)) groups.set(key, {label: item.LABEL || 'Критическая проверка',
                result: item.RESULT, source: item.SOURCE || '', confidence: item.CONFIDENCE || '', rows: []});
            groups.get(key).rows.push(item);
        }
        const signalCount = findings.filter(row => row.RESULT === 'SIGNAL').length;
        const failCount = findings.length - signalCount;
        container.append(node('p', `${plural(failCount, ['строка с нарушением критерия', 'строки с нарушением критерия', 'строк с нарушением критерия'])} · ${plural(signalCount, ['сигнал', 'сигнала', 'сигналов'])} · сгруппировано в ${plural(groups.size, ['группу', 'группы', 'групп'])}.`, 'bp-muted'));
        const ordered = [...groups.values()].sort((a, b) =>
            Number(b.result === 'FAIL') - Number(a.result === 'FAIL')
            || Number(b.confidence === 'HIGH') - Number(a.confidence === 'HIGH')
            || b.rows.length - a.rows.length);
        for (const group of ordered) {
            const details = node('details', '', 'bp-check-group');
            const summary = node('summary', '', 'bp-check-group-summary');
            const heading = node('div', '', 'bp-check-group-heading');
            heading.append(node('strong', group.label));
            heading.append(node('span', pivotLabels[group.source] || group.source || 'Срез не указан', 'bp-muted'));
            summary.append(heading);
            const meta = node('div', '', 'bp-check-group-meta');
            meta.append(node('span', plural(group.rows.length, ['строка', 'строки', 'строк']), 'bp-check-group-count'));
            const argumentsCount = new Set(group.rows.map(row => row.ARGUMENT).filter(Boolean)).size;
            if (argumentsCount) meta.append(node('span', plural(argumentsCount, ['аргумент', 'аргумента', 'аргументов']), 'bp-muted'));
            if (group.confidence) meta.append(node('span', `Уверенность: ${group.confidence}`, 'bp-muted'));
            const isSignal = group.result === 'SIGNAL';
            meta.append(node('span', isSignal ? 'Сигнал обнаружен' : 'Критерий нарушен',
                'bp-check-badge ' + (isSignal ? 'bp-check-signal' : 'bp-check-fail')));
            summary.append(meta);
            details.append(summary);
            const wrap = node('div', '', 'bp-table-wrap');
            const table = node('table');
            table.className = 'bp-check-group-table';
            const columns = node('colgroup');
            for (const width of ['35%', '10%', '11%', '44%']) {
                const column = node('col');
                column.style.width = width;
                columns.append(column);
            }
            table.append(columns);
            const head = table.createTHead().insertRow();
            for (const label of ['Аргумент, условие и факт', 'Заявки', 'Метрика', 'Затронутые заявки']) {
                head.append(node('th', label));
            }
            const body = table.createTBody();
            wrap.append(table);
            details.append(wrap);
            let shown = 0;
            const showNext = () => {
                const next = group.rows.slice(shown, shown + CHECK_GROUP_PAGE_SIZE);
                for (const item of next) renderCheckGroupRow(body, item, comparisonId);
                shown += next.length;
                const remaining = group.rows.length - shown;
                more.hidden = remaining === 0;
                more.textContent = `Показать ещё ${Math.min(CHECK_GROUP_PAGE_SIZE, remaining)} из ${remaining}`;
            };
            const more = button('', showNext, 'bp-button bp-check-group-more');
            more.hidden = true;
            details.append(more);
            details.addEventListener('toggle', () => {
                if (details.open && shown === 0) showNext();
            });
            container.append(details);
        }
        return groups.size;
    }

    /** Добавляет подробности одной строки внутрь её семантической группы. */
    function renderCheckGroupRow(body, item, comparisonId) {
        const tr = body.insertRow();
        const argument = node('td');
        if (item.ARGUMENT) argument.append(node('strong', item.ARGUMENT));
        if (item.CONDITION) argument.append(node('div', item.CONDITION, 'bp-muted'));
        const before = item.VALUE_BEFORE ?? item.valueBefore;
        const after = item.VALUE_AFTER ?? item.valueAfter;
        const actual = item.ACTUAL || (before !== undefined || after !== undefined
            ? `${before ?? '—'} → ${after ?? '—'}` : null);
        if (actual) argument.append(node('div', actual));
        tr.append(argument);
        tr.append(node('td', `${number.format(item.CNT ?? 0)} / ${number.format(item.TOTAL ?? 0)}`));
        const metric = item.METRIC == null || item.METRIC === '' ? NaN : Number(item.METRIC);
        tr.append(node('td', Number.isFinite(metric) ? percentage.format(metric) : '—'));
        const apps = node('td', '', 'bp-check-group-apps');
        const ids = Array.isArray(item.APP_IDS) ? item.APP_IDS : [];
        if (ids.length) {
            const links = node('details', '', 'bp-check-app-links');
            const visibleIds = ids.slice(0, 20);
            links.append(node('summary', plural(visibleIds.length, ['ссылка на заявку', 'ссылки на заявки', 'ссылок на заявки'])));
            const list = node('div', '', 'bp-check-group-app-list');
            for (const appId of visibleIds) {
                const link = node('a', String(appId));
                link.href = '/?' + new URLSearchParams({run: comparisonId, application: String(appId)});
                list.append(link);
            }
            links.append(list);
            if (visibleIds.length < Number(item.CNT || 0)) {
                links.append(node('small', `Показаны ссылки на ${number.format(visibleIds.length)} из ${number.format(item.CNT)} заявок`, 'bp-muted'));
            }
            apps.append(links);
        } else {
            apps.textContent = Number(item.CNT || 0) ? 'Ссылки не предоставлены' : '—';
        }
        tr.append(apps);
    }

    /** Показывает итог каждого выполненного скрипта из сохранённого отчёта запуска. */
    function renderCheckExecutionLog(checks, skipped) {
        const log = q('#bp-check-execution-log');
        log.replaceChildren();
        const entries = [...checks.map(check => ({...check, skipped: false})),
            ...skipped.map(check => ({...check, skipped: true}))];
        const errors = entries.filter(check => check.status === 'ERROR').length;
        const noData = entries.filter(check => check.status === 'NO_DATA').length;
        const skippedCount = entries.filter(check => check.skipped).length;
        q('#bp-check-log-summary').textContent = `${plural(checks.length, ['проверка в журнале', 'проверки в журнале', 'проверок в журнале'])} · ошибок исполнения: ${errors} · без данных: ${noData} · отключено: ${skippedCount}`;
        if (!entries.length) {
            log.append(node('p', 'Сведения о выполнении проверок отсутствуют.', 'bp-muted'));
            return;
        }
        for (const check of entries) {
            const line = node('div', '', 'bp-check-log-entry');
            line.append(node('strong', check.script || check.label || check.id || 'Проверка'));
            if (check.script && check.label && check.script !== check.label) {
                line.append(node('span', check.label, 'bp-muted'));
            }
            const noData = check.status === 'NO_DATA';
            const failed = check.status === 'ERROR';
            const state = check.skipped ? 'Пропущена настройкой'
                : failed ? 'Ошибка исполнения' : noData ? 'Нет данных'
                    : `Выполнена · строк результата: ${number.format(check.row_count ?? 0)}`;
            line.append(node('span', state, 'bp-check-badge ' + (failed ? 'bp-check-fail'
                : check.skipped || noData ? 'bp-check-other' : 'bp-check-pass')));
            if (check.message) line.append(node('p', check.message, 'bp-muted'));
            log.append(line);
        }
    }

    q('#bp-check-dashboard-pivot').addEventListener('click', () => {
        if (activeReport?.pivotAvailable) showPivot(activeReport);
    });


    function showPivot(report) {
        navigate('pivot');
        q('#bp-pivot').hidden = false;
        q('#bp-pivot-back').href = '/?' + new URLSearchParams({run: report.comparisonId});
        const form = q('#bp-pivot-form');
        form.reset();
        const prototype = location.hash === '#bp-pivot-prototype';
        q('#bp-pivot-title').textContent = prototype ? 'Сводная в раскладке прототипа' : 'Сводная по атрибутам';
        all('[data-pivot-layout]').forEach(label => { label.hidden = prototype; });
        all('[data-axis-filter]').forEach(label => { label.hidden = prototype; });
        if (prototype) form.elements.rows.value = 'PATH';
        all('[data-pivot-segment]').forEach(label => {
            label.hidden = !report.segmentsAvailable;
        });
        reloadPivotFilters();
        q('#bp-pivot-title').focus();
        openPivot(pivotLayout());
    }

    function reloadPivotFilters() {
        for (const details of all('#bp-pivot-form details')) details.open = false;
        for (const search of all('#bp-pivot-form input[type="search"]')) search.value = '';
        loadPivotSegmentValues();
    }

    function pivotPath(suffix = '') {
        return '/comparisons/' + activeId + '/pivot' + suffix;
    }

    function updatePivotApply() {
        q('#bp-pivot-form button[type="submit"]').disabled = Boolean(
            q('#bp-pivot-form [data-loading="true"]'));
    }

    async function loadPivotSegmentValues() {
        const segment = q('#bp-pivot-segment').value;
        const filter = q('#bp-pivot-value-filter');
        const options = q('#bp-pivot-value-options');
        const request = ++pivotSegmentRequest;
        q('#bp-pivot-value-search').value = '';
        filter.hidden = !segment;
        options.replaceChildren();
        filter.dataset.loading = String(Boolean(segment));
        updatePivotApply();
        updatePivotValueSummary();
        if (!segment) {
            loadPivotDimensionValues('ROW');
            loadPivotDimensionValues('COLUMN');
            return;
        }
        try {
            const values = await api(pivotPath('/segment-values') + '?segment=' + encodeURIComponent(segment));
            if (request !== pivotSegmentRequest) return;
            for (const value of values) {
                const checkbox = document.createElement('input');
                checkbox.type = 'checkbox';
                checkbox.checked = true;
                checkbox.value = value;
                checkbox.addEventListener('change', () => {
                    updatePivotValueSummary();
                    loadPivotDimensionValues('ROW');
                    loadPivotDimensionValues('COLUMN');
                });
                const label = document.createElement('label');
                label.append(checkbox, node('span', value));
                options.append(label);
            }
            filter.dataset.loading = 'false';
            updatePivotValueSummary();
            loadPivotDimensionValues('ROW');
            loadPivotDimensionValues('COLUMN');
        } catch (error) {
            if (request === pivotSegmentRequest) {
                filter.dataset.loading = 'false';
                options.append(node('p', error.message));
                loadPivotDimensionValues('ROW');
                loadPivotDimensionValues('COLUMN');
            }
        }
    }

    function updatePivotValueSummary() {
        const segment = q('#bp-pivot-segment').value;
        const checks = [...all('#bp-pivot-value-options input[type="checkbox"]')];
        const selected = checks.filter(item => item.checked).length;
        const summary = q('#bp-pivot-value-summary');
        if (!segment) summary.textContent = 'Выберите разрез';
        else if (!checks.length) summary.textContent = 'Нет значений';
        else if (selected === checks.length) summary.textContent = `Все значения · ${number.format(checks.length)}`;
        else if (selected) summary.textContent = `Выбрано ${number.format(selected)} из ${number.format(checks.length)}`;
        else summary.textContent = 'Ничего не выбрано';
    }

    async function loadPivotDimensionValues(axis) {
        const lower = axis.toLocaleLowerCase('en');
        const options = q('#bp-pivot-' + lower + '-options');
        const summary = q('#bp-pivot-' + lower + '-summary');
        const params = new URLSearchParams(new FormData(q('#bp-pivot-form')));
        params.delete('segmentValue');
        params.delete('rowValue');
        params.delete('columnValue');
        params.delete('segment');
        const segment = q('#bp-pivot-segment').value;
        if (segment) {
            params.set('segment', segment);
            for (const item of all('#bp-pivot-value-options input[type="checkbox"]:checked')) {
                params.append('segmentValue', item.value);
            }
        }
        params.set('axis', axis);
        const requestKey = 'pivot' + axis + 'Request';
        const request = (loadPivotDimensionValues[requestKey] || 0) + 1;
        loadPivotDimensionValues[requestKey] = request;
        options.dataset.loading = 'true';
        updatePivotApply();
        options.replaceChildren(node('p', 'Загрузка значений…'));
        summary.textContent = 'Загрузка…';
        try {
            const values = await api(pivotPath('/dimension-values') + '?' + params);
            if (loadPivotDimensionValues[requestKey] !== request) return;
            options.replaceChildren();
            const search = q('#bp-pivot-' + lower + '-search').value.trim().toLocaleLowerCase('ru');
            for (const value of values) {
                const checkbox = document.createElement('input');
                checkbox.type = 'checkbox';
                checkbox.checked = true;
                checkbox.value = value;
                checkbox.addEventListener('change', () => updatePivotDimensionSummary(axis));
                const label = document.createElement('label');
                label.append(checkbox, node('span', pivotDimensionLabel(axis, value)));
                label.hidden = !label.textContent.toLocaleLowerCase('ru').includes(search);
                options.append(label);
            }
            updatePivotDimensionSummary(axis);
        } catch (error) {
            if (loadPivotDimensionValues[requestKey] === request) {
                options.replaceChildren(node('p', error.message));
                summary.textContent = 'Не удалось загрузить';
            }
        } finally {
            if (loadPivotDimensionValues[requestKey] === request) {
                options.dataset.loading = 'false';
                updatePivotApply();
            }
        }
    }

    function pivotDimensionLabel(axis, value) {
        if (axis === 'ROW' && value.startsWith('[')) {
            try { return JSON.parse(value).filter(Boolean).join(' · ') || 'Пустое значение'; } catch { /* Значение не является путём JSON. */ }
        }
        return pivotLabels[value] || value || 'Пустое значение';
    }

    function updatePivotDimensionSummary(axis) {
        const lower = axis.toLocaleLowerCase('en');
        const checks = [...all('#bp-pivot-' + lower + '-options input[type="checkbox"]')];
        const selected = checks.filter(item => item.checked).length;
        const summary = q('#bp-pivot-' + lower + '-summary');
        const noun = axis === 'ROW' ? 'строк' : 'столбцов';
        if (!checks.length) summary.textContent = 'Нет значений';
        else if (selected === checks.length) summary.textContent = `Все ${noun} · ${number.format(checks.length)}`;
        else if (selected) summary.textContent = `Выбрано ${number.format(selected)} из ${number.format(checks.length)}`;
        else summary.textContent = `Ничего не выбрано`;
    }

    function pivotLayout() {
        const layout = new URLSearchParams(new FormData(q('#bp-pivot-form')));
        layout.delete('segmentValue');
        layout.delete('rowValue');
        layout.delete('columnValue');
        layout.delete('filterRows');
        layout.delete('filterColumns');
        const segment = q('#bp-pivot-segment').value;
        const checks = [...all('#bp-pivot-value-options input[type="checkbox"]')];
        const selected = checks.filter(item => item.checked);
        if (!segment || (checks.length > 0 && selected.length === checks.length)) {
            layout.delete('segment');
        } else {
            layout.set('segment', segment);
            selected.forEach(item => layout.append('segmentValue', item.value));
        }
        for (const axis of ['ROW', 'COLUMN']) {
            const lower = axis.toLocaleLowerCase('en');
            const checks = [...all('#bp-pivot-' + lower + '-options input[type="checkbox"]')];
            const checked = checks.filter(item => item.checked);
            if (checks.length && checked.length !== checks.length) {
                layout.set(axis === 'ROW' ? 'filterRows' : 'filterColumns', 'true');
                checked.forEach(item => layout.append(axis === 'ROW' ? 'rowValue' : 'columnValue', item.value));
            }
        }
        return layout;
    }

    function pivotPage(container, page, hasNext, changePage) {
        const previous = button('Назад', () => changePage(page - 1));
        const next = button('Далее', () => changePage(page + 1));
        previous.disabled = page === 0;
        next.disabled = !hasNext;
        const controls = node('div', '', 'bp-page-controls');
        controls.append(previous, node('span', 'Страница ' + (page + 1)), next);
        container.replaceChildren(controls);
    }

    async function openPivot(layout, page = 0) {
        const current = view;
        const request = ++pivotRequest;
        pivotCellRequest += 1;
        pivotFieldRequest += 1;
        const grid = q('#bp-pivot-grid');
        const controls = q('#bp-pivot-page');
        q('#bp-pivot-apps').hidden = true;
        q('#bp-pivot-fields').hidden = true;
        grid.replaceChildren();
        const impact = layout.get('rows') === 'IMPACT';
        const pathAndValue = impact || layout.get('rows') === 'PATH_VALUE';
        const prototype = location.hash === '#bp-pivot-prototype';
        const legend = q('#bp-pivot-legend');
        legend.textContent = impact
            ? 'Поля отсортированы по числу уникальных заявок, в которых изменилось значение или наличие атрибута. '
                + 'Заявка может попасть в несколько ячеек; их суммы могут содержать повторы.'
            : 'В каждой ячейке — число уникальных заявок для этой строки и столбца. '
                + 'Нажмите на число, чтобы открыть заявки.';
        legend.hidden = false;
        controls.textContent = 'Загрузка сводной…';
        try {
            const params = new URLSearchParams(layout);
            params.set('page', page);
            const requests = [api(pivotPath() + '?' + params), api(pivotPath() + '/field-labels')];
            if (pathAndValue) {
                const treeParams = new URLSearchParams(layout);
                requests.push(api(pivotPath() + '/tree-counts?' + treeParams));
            }
            const [pageData, fieldLabels, treeItems = []] = await Promise.all(requests);
            const {items, hasNext} = pageData;
            if (view !== current || request !== pivotRequest) return;
            const groups = new Map();
            const grouped = layout.get('columns') === 'SOURCE_DIFF' && !impact;
            const columns = layout.get('columns') === 'SOURCE_DIFF'
                ? prototype ? ['BEFORE / 0', 'AFTER / 0', 'BEFORE / 1', 'AFTER / 1']
                    : impact ? ['BEFORE / 1', 'AFTER / 1'] : ['BEFORE / 0', 'BEFORE / 1', 'AFTER / 0', 'AFTER / 1']
                : [...new Set(items.map(item => item.column))].sort();
            const isChange = column => impact || (layout.get('columns') === 'SOURCE_DIFF' && column.endsWith(' / 1'))
                || (layout.get('columns') === 'DIFF_FLAG' && column === '1')
                || (layout.get('columns') === 'CHANGE_TYPE' && column !== 'SAME');
            for (const item of items) {
                if (!groups.has(item.row)) {
                    groups.set(item.row, {counts: new Map(), affected: item.affectedApplications});
                }
                groups.get(item.row).counts.set(item.column, item.count);
            }
            const treeGroups = new Map();
            for (const item of treeItems) {
                const key = JSON.stringify([item.level, item.path]);
                if (!treeGroups.has(key)) treeGroups.set(key, new Map());
                treeGroups.get(key).set(item.column, item.count);
            }
            if (!groups.size) {
                controls.textContent = impact ? 'В выбранном разрезе изменений нет.' : 'В выбранном разрезе нет атрибутов.';
                return;
            }
            const table = node('table', '', 'bp-pivot-table');
            const head = table.createTHead();
            const header = head.insertRow();
            const rowLabels = pathAndValue ? ['Путь атрибута', 'Значение / бакет']
                : [q('#bp-pivot-rows').querySelector('option[value="' + layout.get('rows') + '"]').textContent];
            for (const label of rowLabels) {
                const cell = node('th', label);
                cell.scope = 'col';
                cell.rowSpan = grouped ? 2 : 1;
                header.append(cell);
            }
            const columnHeader = grouped ? head.insertRow() : header;
            if (grouped) {
                for (const group of prototype ? ['0', '1'] : ['BEFORE', 'AFTER']) {
                    const cell = node('th', pivotLabels[group]);
                    cell.scope = 'colgroup';
                    cell.colSpan = 2;
                    header.append(cell);
                }
            }
            for (const column of columns) {
                const label = impact && layout.get('columns') === 'SOURCE_DIFF'
                    ? pivotLabels[column.split(' / ')[0]] : pivotLabels[prototype ? column.split(' / ')[0] : grouped ? column.slice(-1) : column] || column;
                const cell = node('th', label);
                cell.scope = 'col';
                columnHeader.append(cell);
            }
            const body = table.createTBody();
            if (pathAndValue && !prototype) {
                const tree = {children: new Map(), parent: null, depth: -1, collapsed: false};
                for (const [label, group] of groups) {
                    const [prefix, attributeClass, suffix, arrayKey, fieldKey, value] = JSON.parse(label);
                    const parts = [
                        {kind: 'prefix', value: prefix},
                        {kind: 'class', value: attributeClass},
                        {kind: 'suffix', value: suffix},
                        {kind: 'array', value: arrayKey},
                        {kind: 'key', value: fieldKey}
                    ].filter(part => part.value);
                    const fullParts = [prefix || '', attributeClass || '', suffix || '', arrayKey || '', fieldKey || ''];
                    let parent = tree;
                    let fullPath = '';
                    for (const part of parts) {
                        if (part.kind !== 'array') fullPath = fullPath ? fullPath + '.' + part.value : part.value;
                        const partKey = part.kind + ':' + part.value;
                        const title = part.kind === 'array' ? 'Ключ массива · ' + part.value : part.value;
                        if (!parent.children.has(partKey)) {
                            const groupPath = fullParts.slice(0, part.kind === 'array' ? 4
                                : part.kind === 'key' ? 5 : ['prefix', 'class', 'suffix'].indexOf(part.kind) + 1);
                            const level = part.kind === 'array' ? 'ARRAY_KEY' : part.kind.toUpperCase();
                            const groupSelection = {level, path: groupPath};
                            parent.children.set(partKey, {label: title, arrayKey: part.kind === 'array', fullPath,
                                children: new Map(), parent, groupSelection,
                                counts: treeGroups.get(JSON.stringify([groupSelection.level, groupPath])) || new Map(),
                                depth: parent.depth + 1, collapsed: false});
                        }
                        parent = parent.children.get(partKey);
                    }
                    const valueKey = '\u0000' + value;
                    if (!parent.children.has(valueKey)) {
                        parent.children.set(valueKey, {label: value || 'Пустое значение', value: true,
                            children: new Map(), parent, depth: parent.depth + 1, collapsed: false,
                            rowKey: label, group,
                            description: parts.map(part => part.kind === 'array' ? 'Ключ массива · ' + part.value : part.value)
                                .concat(value || 'Пустое значение').join(' · ')});
                    }
                }
                const nodes = [];
                const collect = parent => {
                    for (const child of parent.children.values()) {
                        nodes.push(child);
                        collect(child);
                    }
                };
                collect(tree);
                const refreshVisibility = () => {
                    for (const item of nodes) {
                        let parent = item.parent;
                        let hidden = false;
                        while (parent && parent !== tree) {
                            hidden ||= parent.collapsed;
                            parent = parent.parent;
                        }
                        item.row.hidden = hidden;
                    }
                };
                for (const item of nodes) {
                    const row = body.insertRow();
                    item.row = row;
                    if (item.value) {
                        row.classList.add('bp-pivot-tree-value');
                        const pathCell = row.insertCell();
                        pathCell.className = 'bp-pivot-tree-path';
                        pathCell.style.paddingInlineStart = (12 + item.depth * 16) + 'px';
                        pathCell.textContent = '↳';
                        const valueCell = node('th', item.label, 'bp-value');
                        valueCell.scope = 'row';
                        row.append(valueCell);
                        appendPivotCells(row, columns, item.group.counts, layout, item.rowKey, item.description, isChange);
                    } else if (item.arrayKey) {
                        row.classList.add('bp-pivot-tree-node', 'bp-pivot-tree-array-key');
                        const pathCell = row.insertCell();
                        pathCell.className = 'bp-pivot-tree-path';
                        pathCell.style.paddingInlineStart = (12 + item.depth * 16) + 'px';
                        const valueCell = node('th', item.label, 'bp-value');
                        valueCell.scope = 'row';
                        if (item.children.size) {
                            const toggle = button('▾ ' + item.label, () => {
                                item.collapsed = !item.collapsed;
                                toggle.setAttribute('aria-expanded', String(!item.collapsed));
                                toggle.textContent = (item.collapsed ? '▸ ' : '▾ ') + item.label;
                                refreshVisibility();
                            }, 'bp-open bp-pivot-toggle cursor-interaction');
                            toggle.setAttribute('aria-expanded', 'true');
                            valueCell.replaceChildren(toggle);
                        }
                        row.append(valueCell);
                        appendPivotCells(row, columns, item.counts, layout, '', item.label, isChange, item.groupSelection);
                    } else {
                        row.classList.add('bp-pivot-tree-node');
                        const heading = document.createElement('th');
                        heading.scope = 'row';
                        heading.style.paddingInlineStart = (12 + item.depth * 16) + 'px';
                        const title = Object.hasOwn(fieldLabels, item.fullPath)
                            ? fieldLabels[item.fullPath] + ' · ' + item.label : item.label;
                        if (item.children.size) {
                            const toggle = button('▾ ' + title, () => {
                                item.collapsed = !item.collapsed;
                                toggle.setAttribute('aria-expanded', String(!item.collapsed));
                                toggle.textContent = (item.collapsed ? '▸ ' : '▾ ') + title;
                                refreshVisibility();
                            }, 'bp-open bp-pivot-toggle cursor-interaction');
                            toggle.setAttribute('aria-expanded', 'true');
                            heading.append(toggle);
                        } else {
                            heading.textContent = title;
                        }
                        row.append(heading, document.createElement('td'));
                        appendPivotCells(row, columns, item.counts, layout, '', title, isChange, item.groupSelection);
                    }
                }
            } else {
                for (const [label, group] of groups) {
                    const row = body.insertRow();
                    const values = pathAndValue ? JSON.parse(label) : [label];
                    if (pathAndValue || layout.get('rows') === 'PATH') {
                        const title = Object.hasOwn(fieldLabels, values[0]) ? fieldLabels[values[0]] : '';
                        if (title) values[0] = title + ' · ' + values[0];
                    }
                    const description = values.map(value => value || 'Пустое значение').join(' · ');
                    for (const value of values) {
                        const heading = node('th', value || 'Пустое значение', 'bp-value');
                        heading.scope = 'row';
                        if (prototype) {
                            const toggle = button('▸ ' + value, () => {
                                if (toggle.getAttribute('aria-expanded') === 'true') {
                                    toggle.setAttribute('aria-expanded', 'false');
                                    toggle.textContent = '▸ ' + value;
                                    row.nextElementSibling.remove();
                                    return;
                                }
                                toggle.setAttribute('aria-expanded', 'true');
                                toggle.textContent = '▾ ' + value;
                                const detail = document.createElement('tr');
                                const content = detail.insertCell();
                                content.colSpan = columns.length + 1;
                                row.after(detail);
                                const selected = new URLSearchParams(layout);
                                selected.set('rows', 'FIELD_VALUES');
                                selected.set('prefix', label);
                                openPrototypeValues(content, selected, columns, isChange);
                            }, 'bp-open bp-pivot-toggle cursor-interaction');
                            toggle.setAttribute('aria-expanded', 'false');
                            heading.replaceChildren(toggle);
                        }
                        row.append(heading);
                    }
                    appendPivotCells(row, columns, group.counts, layout, label, description, isChange);
                }
            }
            grid.replaceChildren(table);
            pivotPage(controls, page, hasNext, next => openPivot(layout, next));
        } catch (error) {
            if (view === current && request === pivotRequest) controls.textContent = error.message;
        }
    }

    function appendPivotCells(row, columns, counts, layout, label, description, isChange, groupSelection = null) {
        for (const column of columns) {
            const cell = row.insertCell();
            const count = counts.get(column);
            if (count === undefined) {
                cell.textContent = '—';
                continue;
            }
            if (isChange(column)) cell.classList.add('bp-pivot-changed');
            const target = button(number.format(count), () => {
                const selection = new URLSearchParams(layout);
                if (groupSelection) {
                    selection.set('groupLevel', groupSelection.level);
                    groupSelection.path.forEach(part => selection.append('groupPath', part));
                } else {
                    selection.set('row', label);
                }
                selection.set('column', column);
                openPivotApps(selection, count);
            }, 'bp-open cursor-interaction');
            target.setAttribute('aria-label', number.format(count) + ' заявок · '
                + description + ' · ' + (pivotLabels[column] || column));
            cell.append(target);
        }
    }

    async function openPrototypeValues(content, layout, columns, isChange, page = 0) {
        const current = view;
        content.textContent = 'Загрузка значений…';
        try {
            const params = new URLSearchParams(layout);
            params.set('page', page);
            const {items, hasNext} = await api(pivotPath() + '?' + params);
            if (view !== current || !content.isConnected) return;
            const values = new Map();
            for (const item of items) {
                if (!values.has(item.row)) values.set(item.row, new Map());
                values.get(item.row).set(item.column, item.count);
            }
            const table = node('table', '', 'bp-pivot-table bp-prototype-values');
            table.createTHead().insertRow().append(node('th', 'Значение / бакет'),
                ...columns.map(column => node('th', pivotLabels[column])));
            const body = table.createTBody();
            for (const [key, counts] of values) {
                const [prefix, attributeClass, suffix, arrayKey, fieldKey, value] = JSON.parse(key);
                const path = [prefix, attributeClass, suffix, fieldKey].filter(Boolean).join('.');
                const label = value || 'Пустое значение';
                const row = body.insertRow();
                const heading = node('th', label);
                heading.scope = 'row';
                row.append(heading);
                appendPivotCells(row, columns, counts, layout, key,
                    path + (arrayKey ? ' · Ключ массива · ' + arrayKey : '') + ' · ' + label, isChange);
            }
            const controls = document.createElement('div');
            content.replaceChildren(table, controls);
            if (page > 0 || hasNext) {
                pivotPage(controls, page, hasNext, next => openPrototypeValues(content, layout, columns, isChange, next));
            }
        } catch (error) {
            if (view === current && content.isConnected) content.textContent = error.message;
        }
    }

    async function openPivotApps(selection, count, page = 0) {
        const current = view;
        const request = ++pivotCellRequest;
        pivotFieldRequest += 1;
        const target = q('#bp-pivot-apps');
        q('#bp-pivot-fields').hidden = true;
        target.hidden = false;
        let label = selection.get('row');
        if (selection.has('groupLevel')) {
            const parts = selection.getAll('groupPath');
            const level = selection.get('groupLevel');
            label = parts.slice(0, 3).filter(Boolean).join(' · ');
            if (level === 'ARRAY_KEY' && parts[3]) label += ' · Ключ массива · ' + parts[3];
            if (level === 'KEY') {
                if (parts[3]) label += ' · Ключ массива · ' + parts[3];
                if (parts[4]) label += ' · ' + parts[4];
            }
            if (level === 'CLASS') label = parts.slice(0, 2).filter(Boolean).join(' · ');
            if (level === 'PREFIX') label = parts[0] || 'Объект';
        } else if (['PATH_VALUE', 'FIELD_VALUES', 'IMPACT'].includes(selection.get('rows'))) {
            const parts = JSON.parse(selection.get('row'));
            const value = parts.pop();
            const arrayKey = parts[3];
            label = parts.filter((part, index) => part && index !== 3).join(' · ')
                + (arrayKey ? ' · Ключ массива · ' + arrayKey : '')
                + ' · ' + (value || 'Пустое значение');
        }
        const heading = node('h3', (label || 'Пустое значение') + ' · '
            + (pivotLabels[selection.get('column')] || selection.get('column')) + ' · ' + number.format(count) + ' заявок');
        heading.tabIndex = -1;
        const content = node('div', 'Загрузка заявок…');
        target.replaceChildren(heading, content);
        heading.focus();
        try {
            const params = new URLSearchParams(selection);
            params.set('page', page);
            const {items, hasNext} = await api(pivotPath('/applications') + '?' + params);
            if (view !== current || request !== pivotCellRequest) return;
            const list = node('ul', '', 'bp-pivot-app-list');
            for (const id of items) {
                const entry = document.createElement('li');
                entry.append(button(id, () => openPivotFields(selection, id), 'bp-open cursor-interaction'));
                list.append(entry);
            }
            const controls = document.createElement('div');
            content.replaceChildren(list, controls);
            pivotPage(controls, page, hasNext, next => openPivotApps(selection, count, next));
        } catch (error) {
            if (view === current && request === pivotCellRequest) content.textContent = error.message;
        }
    }

    async function openPivotFields(selection, id, page = 0) {
        const current = view;
        const request = ++pivotFieldRequest;
        const target = q('#bp-pivot-fields');
        target.hidden = false;
        const heading = node('h3', 'Поля заявки ' + id + ' в выбранной ячейке');
        heading.tabIndex = -1;
        const content = node('div', 'Загрузка полей…');
        target.replaceChildren(heading, content);
        heading.focus();
        try {
            const params = new URLSearchParams(selection);
            params.set('page', page);
            const {items, hasNext} = await api(pivotPath('/applications/' + encodeURIComponent(id) + '/attributes')
                + '?' + params);
            if (view !== current || request !== pivotFieldRequest) return;
            const wrapper = node('div', '', 'bp-table-wrap');
            wrapper.append(attributeTable(items));
            const controls = document.createElement('div');
            const link = node('a', 'Все отличия заявки', 'bp-button');
            link.href = '/?' + new URLSearchParams({run: activeId, application: id});
            content.replaceChildren(wrapper, controls, link);
            pivotPage(controls, page, hasNext, next => openPivotFields(selection, id, next));
        } catch (error) {
            if (view === current && request === pivotFieldRequest) content.textContent = error.message;
        }
    }

    function attributeTable(attributes) {
        const table = document.createElement('table');
        const header = table.createTHead().insertRow();
        ['Путь поля', 'Ключ массива', 'До', 'После', 'Изменение'].forEach(label => header.append(node('th', label)));
        const rows = table.createTBody();
        for (const diff of attributes) {
            rows.insertRow().append(node('td', diff.path, 'bp-value'), node('td', diff.arrayKey || '—', 'bp-value'),
                valueCell(diff.valueBefore), valueCell(diff.valueAfter), node('td', changes[diff.changeType]));
        }
        return table;
    }

    function approval(side, count, total) {
        const percent = total ? 100 * count / total : 0;
        q('#bp-approve-' + side).textContent = number.format(count) + ' из ' + number.format(total)
            + ' · ' + ratio.format(percent) + '%';
        q('#bp-bar-' + side).style.width = percent + '%';
        return percent;
    }

    function closeDetail() {
        detailRequest += 1;
        q('#bp-detail').hidden = true;
    }

    async function openApplications(group, page = 0) {
        const current = view;
        const request = ++listRequest;
        const [label, filters] = groups[group];
        const parameters = new URLSearchParams({...filters, page});
        status();
        closeDetail();
        all('[data-group]').forEach(item => item.setAttribute('aria-pressed', String(item.dataset.group === group)));
        q('#bp-list-title').textContent = label;
        q('#bp-list-footer').textContent = 'Загрузка заявок…';
        q('#bp-rows').replaceChildren();
        q('#bp-list').hidden = false;
        q('#bp-list-title').focus();
        try {
            const result = await api('/comparisons/' + activeId + '/applications?' + parameters);
            if (view !== current || listRequest !== request) return;
            q('#bp-list-title').textContent = label + ' · ' + number.format(result.page.totalElements);
            q('#bp-rows').replaceChildren(...result.items.map(item => {
                const row = document.createElement('tr');
                const idCell = document.createElement('td');
                idCell.append(button(item.applicationId, event => {
                    selectedRow = event.currentTarget;
                    openDetail(item.applicationId);
                }, 'bp-open cursor-interaction'));
                row.append(idCell, node('td', decision(item.decisionBefore)), node('td', decision(item.decisionAfter)),
                    node('td', item.hasDiff ? 'Есть' : 'Нет'));
                return row;
            }));
            paginate(q('#bp-list-footer'), result.page, next => openApplications(group, next));
        } catch (error) {
            if (view === current && listRequest === request) q('#bp-list-footer').textContent = error.message;
        }
    }

    function decision(value) {
        return value === 'APPROVE' ? 'Одобрение' : 'Отказ';
    }

    async function openDetail(applicationId) {
        const current = view;
        const request = ++detailRequest;
        const content = q('#bp-detail-content');
        q('#bp-detail-title').textContent = 'Заявка ' + applicationId;
        content.textContent = 'Загрузка отличий…';
        q('#bp-detail').hidden = false;
        q('#bp-detail-title').focus();
        try {
            const item = await api('/comparisons/' + activeId + '/applications/' + encodeURIComponent(applicationId));
            if (view !== current || detailRequest !== request) return;
            content.replaceChildren(node('p', 'Решение: ' + decision(item.decisionBefore) + ' → '
                + decision(item.decisionAfter), 'bp-reason'));
            if (item.differences.length === 0) {
                content.append(node('p', 'Отличий по полям нет.'));
                return;
            }
            const wrapper = node('div', '', 'bp-table-wrap');
            wrapper.append(attributeTable(item.differences));
            content.append(wrapper);
        } catch (error) {
            if (view === current && detailRequest === request) content.textContent = error.message;
        }
    }

    function valueCell(value) {
        const text = value === null ? 'Поле отсутствует' : value === '' ? 'Пустая строка' : value;
        const cell = document.createElement('td');
        cell.append(node(value === null || value === '' ? 'em' : 'code', text, 'bp-value'));
        return cell;
    }

    function paginate(container, page, changePage) {
        container.replaceChildren();
        if (page.totalPages === 0) {
            container.textContent = 'Нет записей';
            return;
        }
        const controls = node('div', '', 'bp-page-controls');
        const previous = button('Назад', () => changePage(page.page - 1));
        const next = button('Далее', () => changePage(page.page + 1));
        previous.disabled = page.page === 0;
        next.disabled = page.page + 1 >= page.totalPages;
        controls.append(previous, node('span', 'Страница ' + (page.page + 1) + ' из ' + page.totalPages), next);
        container.append(controls);
    }

    async function openLibrary(section, page = 0) {
        updateRunLocation();
        const current = navigate(section);
        const content = q('#bp-library-content');
        content.textContent = 'Загрузка…';
        try {
            const result = await api(section === 'history' ? '/comparisons?page=' + page : '/datasets?page=' + page);
            if (view !== current) return;
            const entries = document.createElement('ul');
            for (const item of result.items) {
                const entry = document.createElement('li');
                if (section === 'history') {
                    const label = statuses[item.status] + ' · ' + date.format(new Date(item.createdAt)) + ' · ' + item.id;
                    entry.append(button(label, () => openRun(item.id), 'bp-open cursor-interaction'));
                } else {
                    entry.textContent = item.name + ' · ' + number.format(item.applicationCount) + ' заявок';
                }
                entries.append(entry);
            }
            const controls = document.createElement('div');
            paginate(controls, result.page, next => openLibrary(section, next));
            content.replaceChildren(entries, controls);
        } catch (error) {
            if (view === current) content.textContent = error.message;
        }
    }

    function showComparison() {
        if (activeId) openRun(activeId);
        else openForm();
    }

    function applyTheme(value) {
        const theme = value === 'dark' ? 'dark' : 'light';
        root.dataset.theme = theme;
        all('[data-theme-choice]').forEach(item => item.setAttribute('aria-pressed', String(item.dataset.themeChoice === theme)));
    }

    all('[data-theme-choice]').forEach(item => item.addEventListener('click', () => {
        applyTheme(item.dataset.themeChoice);
        try { localStorage.setItem('balanceplatform.theme', root.dataset.theme); } catch { /* Тема действует до перезагрузки. */ }
    }));
    try { applyTheme(localStorage.getItem('balanceplatform.theme')); } catch { applyTheme('light'); }
    all('[data-section]').forEach(item => item.addEventListener('click', () => {
        if (item.dataset.section === 'report') showComparison();
        else openLibrary(item.dataset.section);
    }));
    all('[data-group]').forEach(item => item.addEventListener('click', () => openApplications(item.dataset.group)));
    q('#bp-pivot-form').addEventListener('submit', event => {
        event.preventDefault();
        if (!q('#bp-pivot-form button[type="submit"]').disabled) openPivot(pivotLayout());
    });
    q('#bp-pivot-reset').addEventListener('click', () => {
        const form = q('#bp-pivot-form');
        form.elements.prefix.value = '';
        form.elements.segment.value = '';
        reloadPivotFilters();
        openPivot(pivotLayout());
    });
    q('#bp-pivot-segment').addEventListener('change', loadPivotSegmentValues);
    q('#bp-pivot-rows').addEventListener('change', () => loadPivotDimensionValues('ROW'));
    q('#bp-pivot-form').elements.columns.addEventListener('change', () => loadPivotDimensionValues('COLUMN'));
    q('#bp-pivot-value-search').addEventListener('input', event => {
        const value = event.currentTarget.value.trim().toLocaleLowerCase('ru');
        for (const option of all('#bp-pivot-value-options > label')) {
            option.hidden = !option.textContent.toLocaleLowerCase('ru').includes(value);
        }
    });
    q('#bp-pivot-select-all').addEventListener('click', () => {
        all('#bp-pivot-value-options input[type="checkbox"]').forEach(item => { item.checked = true; });
        updatePivotValueSummary();
        loadPivotDimensionValues('ROW');
        loadPivotDimensionValues('COLUMN');
    });
    q('#bp-pivot-clear-values').addEventListener('click', () => {
        all('#bp-pivot-value-options input[type="checkbox"]').forEach(item => { item.checked = false; });
        updatePivotValueSummary();
        loadPivotDimensionValues('ROW');
        loadPivotDimensionValues('COLUMN');
    });
    for (const axis of ['ROW', 'COLUMN']) {
        const lower = axis.toLocaleLowerCase('en');
        q('#bp-pivot-' + lower + '-search').addEventListener('input', event => {
            const value = event.currentTarget.value.trim().toLocaleLowerCase('ru');
            for (const option of all('#bp-pivot-' + lower + '-options > label')) {
                option.hidden = !option.textContent.toLocaleLowerCase('ru').includes(value);
            }
        });
        q('[data-axis-select-all="' + axis + '"]').addEventListener('click', () => {
            all('#bp-pivot-' + lower + '-options input[type="checkbox"]').forEach(item => { item.checked = true; });
            updatePivotDimensionSummary(axis);
        });
        q('[data-axis-clear="' + axis + '"]').addEventListener('click', () => {
            all('#bp-pivot-' + lower + '-options input[type="checkbox"]').forEach(item => { item.checked = false; });
            updatePivotDimensionSummary(axis);
        });
    }
    q('#bp-rule-add').addEventListener('click', () => {
        if (q('#bp-configuration-checks').children.length >= 40) {
            q('#bp-configuration-status').textContent = 'Достигнут лимит плана проверок.';
            return;
        }
        const identifier = [...crypto.getRandomValues(new Uint32Array(4))].map(value => value.toString(16).padStart(8, '0')).join('');
        appendCheckRow({id: 'rule:' + identifier, enabled: true,
            rule: {type: 'threshold', source: 'AFTER'}});
        renderPresetSummary();
    });
    q('#bp-configuration-checks').addEventListener('change', renderPresetSummary);
    q('#bp-configuration-checks').addEventListener('input', renderPresetSummary);
    q('#bp-analysis-add').addEventListener('click', () => {
        clearNormalizationPreview();
        appendAnalysisField();
    });
    q('#bp-analysis-rows').addEventListener('input', clearNormalizationPreview);
    q('#bp-analysis-preview-button').addEventListener('click', () => showNormalizationPreview(true));
    q('#bp-normalization-add').addEventListener('click', () => {
        clearNormalizationPreview();
        appendNormalizationRule();
    });
    q('#bp-normalization-rows').addEventListener('input', clearNormalizationPreview);
    for (const selector of ['#bp-configuration-normalizer', '#bp-configuration-procedure',
        '#bp-normalizer-code', '#bp-procedure-code']) {
        q(selector).addEventListener('input', clearNormalizationPreview);
    }
    q('#bp-normalization-preview-button').addEventListener('click', () => showNormalizationPreview());
    q('#bp-script-target').addEventListener('change', renderScriptCatalog);
    q('#bp-script-file').addEventListener('change', () => {
        scriptRequest += 1;
        q('#bp-script-status').textContent = '';
    });
    q('#bp-script-copy').addEventListener('click', loadScriptSource);
    q('#bp-script-validate').addEventListener('click', validateScriptEditor);
    for (const selector of Object.values(scriptEditors)) {
        q(selector).addEventListener('input', () => {
            scriptRequest += 1;
            q('#bp-script-status').textContent = '';
            renderScriptDiff();
            if (selector === '#bp-check-code') renderPresetSummary();
        });
    }
    for (const editor of all('[data-expert-only] textarea')) {
        editor.addEventListener('input', renderInlineSummary);
    }
    q('#bp-wizard-back').addEventListener('click', () => showWizardStep(wizardStep - 1, true));
    q('#bp-wizard-next').addEventListener('click', () => showWizardStep(wizardStep + 1, true));
    q('#bp-dataset').addEventListener('change', () => {
        clearNormalizationPreview();
        showWizardStep(wizardStep);
    });
    q('#bp-configuration').addEventListener('change', () => {
        const previous = q('#bp-dataset').value;
        renderConfiguration();
        const selected = q('#bp-dataset').value;
        const step = !selected ? 0 : selected !== previous ? 1 : wizardStep;
        showWizardStep(step, step !== wizardStep);
        if (!selected) {
            q('#bp-configuration-status').textContent = 'Набор сохранённой конфигурации недоступен. Выберите другой набор.';
        }
    });
    q('#bp-save-configuration').addEventListener('click', () => saveConfiguration(false));
    q('#bp-copy-configuration').addEventListener('click', () => saveConfiguration(true));
    q('#bp-form').addEventListener('submit', startComparison);
    q('#bp-new').addEventListener('click', openForm);
    q('#bp-cancel').addEventListener('click', () => {
        if (activeId) openRun(activeId);
        else {
            navigate('report');
            status('Выберите «Новое сравнение», чтобы запустить обработку.');
        }
    });
    q('#bp-replay').addEventListener('click', replayFromJournal);
    q('#bp-stage-source-close').addEventListener('click', () => {
        stageSourceRequest += 1;
        q('#bp-stage-source').hidden = true;
    });
    q('#bp-close-detail').addEventListener('click', () => {
        updateRunLocation(activeId);
        closeDetail();
        selectedRow?.focus();
    });

    globalThis.lucide?.createIcons({attrs: {width: 16, height: 16}});
    function openLocation() {
        const deepLink = new URLSearchParams(location.search);
        const requestedRun = deepLink.get('run');
        if (requestedRun && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(requestedRun)) {
            openRun(requestedRun, undefined, deepLink.get('application'), true);
        } else {
            openForm();
        }
    }

    window.addEventListener('hashchange', openLocation);
    openLocation();
})();
