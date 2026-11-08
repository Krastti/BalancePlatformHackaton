package com.kratti.controller;

import com.kratti.service.DatasetComparisonService;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/** Публикует сохранённый отчёт проверок в браузере и как автономный HTML. */
@RestController
@RequiredArgsConstructor
public class DatasetCheckReportController {
    private static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    private final DatasetComparisonService comparisons;

    /** Возвращает все строки проверок и список выполненных скриптов. */
    @GetMapping("/api/comparisons/{id}/checks")
    public JsonNode checks(@PathVariable UUID id) {
        return comparisons.checkReport(id);
    }

    /** Отдаёт HTML со ссылками на сводку прогона и заявки из результатов проверок. */
    @GetMapping("/api/comparisons/{id}/checks.html")
    public ResponseEntity<String> html(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean download) {
        JsonNode report = comparisons.checkReport(id);
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().path("/")
                .queryParam("run", id).build().toUriString();
        String export = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .queryParam("download", true).build().toUriString();
        var response = ResponseEntity.ok().contentType(HTML_UTF8);
        if (download) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename("balance-checks-" + id + ".html").build().toString());
        }
        return response.body(render(report, base, export));
    }

    /** Собирает документ без внешних ресурсов и экранирует данные проверок. */
    private String render(JsonNode report, String base, String export) {
        JsonNode rows = report.path("rows");
        long passed = 0;
        long failed = 0;
        long signaled = 0;
        Set<List<String>> findingGroups = new HashSet<>();
        for (JsonNode row : rows) {
            if ("PASS".equals(row.path("RESULT").asString())) passed++;
            String result = row.path("RESULT").asString();
            if ("FAIL".equals(result)) failed++;
            if ("SIGNAL".equals(result)) signaled++;
            if ("FAIL".equals(result) || "SIGNAL".equals(result)) {
                findingGroups.add(List.of(result, row.path("LABEL").asString(), row.path("SOURCE").asString(),
                        row.path("CONFIDENCE").asString()));
            }
        }
        long criteriaRows = passed + failed;
        String passRate = criteriaRows == 0 ? "—"
                : String.format(java.util.Locale.ROOT, "%.1f%%", passed * 100.0 / criteriaRows);
        JsonNode checks = report.path("checks");
        var html = new StringBuilder("""
                <!doctype html><html lang="ru"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Отчёт проверок · BalancePlatform</title>
                <style>
                :root{color-scheme:light dark;--bg:light-dark(#fffdf9,#24231f);--fg:light-dark(#202a27,#e9eee9);--muted:light-dark(#65716b,#abb5ae);--line:light-dark(#d9ded7,#4a514c);--head:light-dark(#f2f4ef,#303630);--green:light-dark(#286447,#90d4a5);--green-bg:light-dark(#e6f3e8,#293d30);--red:light-dark(#a53b2f,#ffaaa0);--red-bg:light-dark(#fae9e5,#4b302c);--amber:light-dark(#8b5a13,#f0c775);--amber-bg:light-dark(#fbf1da,#433a27)}
                *{box-sizing:border-box}body{font:14px/1.45 system-ui,sans-serif;margin:2rem;color:var(--fg);background:var(--bg)}
                h1{font-size:1.25rem;margin:0 0 .25rem}p{color:var(--muted);margin:.2rem 0 1rem}
                nav{display:flex;gap:.75rem;flex-wrap:wrap;margin:1rem 0}a{color:var(--green)}nav a{display:inline-flex;align-items:center;border:1px solid var(--line);border-radius:5px;padding:.5rem .75rem;background:var(--head);color:var(--fg);text-decoration:none;font-weight:600}nav a:hover{border-color:var(--green)}nav a:last-child{background:var(--green);border-color:var(--green);color:var(--bg)}
                .overview{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,22rem),1fr));gap:.75rem;margin:1rem 0}.overview-card{border:1px solid var(--line);border-radius:8px;padding:.9rem;background:var(--head)}.overview-card h2{font-size:.95rem;margin:0 0 .5rem}.overview-card p{margin:.55rem 0 0}
                .summary{display:flex;gap:.5rem;flex-wrap:wrap;margin:.5rem 0}.summary span,.badge{display:inline-block;border-radius:5px;padding:.3rem .55rem;font-size:.85rem;font-weight:600;background:var(--head)}
                .summary .pass,.badge.pass{color:var(--green);background:var(--green-bg)}.summary .signal,.badge.signal{color:var(--amber);background:var(--amber-bg)}.summary .fail,.badge.fail,.summary .error,.badge.error{color:var(--red);background:var(--red-bg)}.summary .other,.badge.other{color:var(--muted);background:var(--head)}
                .table{overflow:auto}table{border-collapse:collapse;background:var(--bg);width:100%;min-width:820px}th,td{text-align:left;vertical-align:top;padding:.65rem .75rem;border-bottom:1px solid var(--line)}
                th{background:var(--head);color:var(--muted);font-size:.75rem;letter-spacing:.035em;white-space:nowrap}tbody tr:hover{background:var(--head)}td.number{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
                th:first-child{min-width:13rem}th:last-child{min-width:9rem}td a{display:inline-block;margin-right:.4rem}summary{cursor:pointer;color:var(--green)}details div{display:flex;gap:.3rem;flex-wrap:wrap;margin-top:.4rem}.muted{color:var(--muted)}
                </style></head><body><h1>Результаты проверок</h1><p>Отчёт отдельно показывает выполнение скриптов, нарушения критериев и обнаруженные изменения.</p><nav>
                """);
        appendLink(html, base + "#bp-pivot", "Открыть сводную приложения");
        appendLink(html, export, "Скачать HTML");
        html.append("</nav><div class=overview><section class=overview-card><h2>Исполнение проверок</h2><div class=summary><span class=pass>Проверки в журнале: ")
                .append(checks.size()).append("</span><span class=error>Ошибок исполнения: ");
        long noData = 0;
        long errors = 0;
        for (JsonNode check : checks) {
            if ("NO_DATA".equals(check.path("status").asString())) noData++;
            if ("ERROR".equals(check.path("status").asString())) errors++;
        }
        long skipped = report.path("skipped").size();
        html.append(errors).append("</span><span class=other>Без данных: ").append(noData).append("</span><span class=other>Отключено: ")
                .append(skipped).append("</span></div><p>")
                .append(errors > 0 ? "Есть проверки, которые не удалось выполнить."
                        : checks.isEmpty() ? "В этом запуске нет выполненных проверок."
                                : "Проверки завершились без ошибок исполнения.")
                .append("</p></section><section class=overview-card><h2>Результаты проверок</h2><div class=summary><span class=pass>PASS: ")
                .append(passed).append("</span><span class=fail>FAIL: ").append(failed)
                .append("</span><span class=signal>SIGNAL: ").append(signaled)
                .append("</span><span class=other>Группы: ").append(findingGroups.size())
                .append("</span><span class=pass>Доля PASS: ").append(passRate)
                .append("</span></div><p>Доля рассчитана по строкам PASS/FAIL. SIGNAL отражает найденные изменения и не входит в расчёт.</p></section></div>")
                .append("<h2>Состояние проверок</h2><ul>");
        for (JsonNode check : report.path("checks")) {
            html.append("<li>").append(escape(check.path("label").asString())).append(" · ");
            if ("NO_DATA".equals(check.path("status").asString())) {
                html.append("<span class=badge other>Нет данных</span>");
            } else if ("ERROR".equals(check.path("status").asString())) {
                html.append("<span class=badge error>Ошибка исполнения</span>");
                if (!check.path("message").asString().isBlank()) {
                    html.append("<br><small class=muted>").append(escape(check.path("message").asString())).append("</small>");
                }
            } else {
                html.append("Выполнена, строк: ").append(check.path("row_count").asInt());
            }
            html.append("</li>");
        }
        for (JsonNode check : report.path("skipped")) {
            html.append("<li>").append(escape(check.path("label").asString()))
                    .append(" · <span class=badge other>Пропущена по настройке</span></li>");
        }
        html.append("</ul>");
        if (report.path("rows").isEmpty()) {
            return html.append("<p>В этом запуске нет строк с вердиктами.</p></body></html>").toString();
        }
        html.append("<h2>Результаты проверок</h2><div class=table><table><thead><tr>");
        for (String name : List.of("Проверка", "Срез", "Условие / атрибут", "Фактическое значение", "Результат", "Заявки")) {
            html.append("<th scope=\"col\">").append(name).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (JsonNode row : report.path("rows")) {
            String result = row.path("RESULT").asString();
            String verdict = switch (result) {
                case "PASS" -> "Критерий соблюдён";
                case "SIGNAL" -> "Сигнал обнаружен";
                default -> "Критерий нарушен";
            };
            String badge = switch (result) {
                case "PASS" -> "pass";
                case "SIGNAL" -> "signal";
                default -> "fail";
            };
            html.append("<tr><td>").append(escape(row.path("LABEL").asString())).append("</td><td>")
                    .append(escape(row.path("SOURCE").asString())).append("</td><td>")
                    .append(escape(row.path("CONDITION").asString(row.path("LABEL").asString())));
            String argument = row.path("ARGUMENT").asString();
            if (!argument.isBlank() && !argument.equals(row.path("ACTUAL").asString())) {
                html.append("<br><small class=muted>Параметр: ").append(escape(argument)).append("</small>");
            }
            String actual = row.path("ACTUAL").asString();
            String shownActual = actual.isBlank()
                    ? String.format(java.util.Locale.ROOT, "%.1f%%", row.path("METRIC").asDouble() * 100)
                    : actual;
            html.append("</td><td>").append(escape(shownActual))
                    .append("<br><small class=muted>Заявки: ").append(row.path("CNT").asLong()).append(" из ")
                    .append(row.path("TOTAL").asLong())
                    .append("</small></td><td><span class=\"badge ").append(badge)
                    .append("\">").append(verdict).append("</span>");
            if ("LOW".equals(row.path("CONFIDENCE").asString())) {
                html.append("<small class=muted>Мало наблюдений</small>");
            }
            html.append("</td>");
            html.append("<td>");
            JsonNode apps = row.path("APP_IDS");
            if (apps.isEmpty()) {
                html.append(row.path("CNT").asLong() > 0 ? "Ссылки не предоставлены" : "—");
            } else {
                html.append("<details><summary>Показать ссылки (").append(apps.size()).append(")</summary>");
                if (apps.size() < row.path("CNT").asLong()) {
                    html.append("<small class=muted>Доступно ").append(apps.size()).append(" из ")
                            .append(row.path("CNT").asLong()).append(" затронутых заявок</small>");
                }
                html.append("<div>");
                for (JsonNode app : apps) {
                    String id = app.asString();
                    String link = UriComponentsBuilder.fromUriString(base).queryParam("application", "{id}")
                            .encode().buildAndExpand(id).toUriString();
                    appendLink(html, link, id);
                }
                html.append("</div></details>");
            }
            html.append("</td></tr>");
        }
        return html.append("</tbody></table></div><p>Ошибка отдельной проверки помечается как ERROR и не считается бизнес-нарушением; остальные проверки продолжают выполняться. Если не удалось выполнить весь этап, отчёт не публикуется, а причина указана в статусе запуска.</p></body></html>").toString();
    }

    /** Добавляет безопасную текстовую ссылку в отчёт. */
    private void appendLink(StringBuilder html, String url, String label) {
        html.append("<a href=\"").append(escape(url)).append("\">")
                .append(escape(label)).append("</a>");
    }

    /** Экранирует динамическое значение для HTML. */
    private String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
