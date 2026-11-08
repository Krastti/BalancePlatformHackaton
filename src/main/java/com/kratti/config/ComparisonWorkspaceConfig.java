package com.kratti.config;

import com.kratti.exception.ApiException;
import com.kratti.repository.DatasetComparisonRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Изолирует новые сравнения по рабочей области браузера. */
@Configuration
@RequiredArgsConstructor
public class ComparisonWorkspaceConfig implements WebMvcConfigurer {
    public static final String WORKSPACE = "balance.workspace";
    private static final String COOKIE = "BALANCE_WORKSPACE";
    private final DatasetComparisonRepository repository;

    /** Применяет проверку рабочей области к запускам и всем связанным отчётам. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            /** Сохраняет ключ рабочей области и проверяет право на запрошенный запуск. */
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                UUID owner = workspace(request, response);
                if ("POST".equals(request.getMethod()) && request.getAttribute(WORKSPACE) == null) {
                    throw new ApiException(428, "WORKSPACE_REQUIRED", "Обновите рабочую область перед запуском");
                }
                request.setAttribute(WORKSPACE, owner);

                Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                if (variables instanceof Map<?, ?> values && values.get("id") instanceof String key) {
                    UUID id = parse(key);
                    if (id == null) {
                        throw new ApiException(400, "BAD_REQUEST", "Некорректный UUID");
                    }
                    if (!repository.canView(id, owner)) {
                        throw new ApiException(404, "COMPARISON_NOT_FOUND", "Запуск сравнения не найден");
                    }
                }
                return true;
            }
        }).addPathPatterns("/api/comparisons", "/api/comparisons/**");
    }

    /** Возвращает действующий ключ браузера или выдаёт новый случайный ключ. */
    private UUID workspace(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName())) {
                    UUID id = parse(cookie.getValue());
                    if (id != null) {
                        request.setAttribute(WORKSPACE, id);
                        return id;
                    }
                }
            }
        }
        UUID id = UUID.randomUUID();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, id.toString())
                .httpOnly(true).secure(request.isSecure()).sameSite("Lax")
                .path(request.getContextPath().isEmpty() ? "/" : request.getContextPath())
                .maxAge(Duration.ofDays(30)).build().toString());
        return id;
    }

    /** Принимает только UUID в полной канонической записи. */
    private UUID parse(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
