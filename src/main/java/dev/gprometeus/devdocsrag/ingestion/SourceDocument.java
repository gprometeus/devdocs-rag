package dev.gprometeus.devdocsrag.ingestion;

import java.util.Objects;

/**
 * Документ из любого источника, приведённый к общему виду.
 * Контракт между загрузкой (шаг 2.2) и нарезкой (шаг 2.3).
 * Поля соответствуют колонкам таблицы document.
 */
public record SourceDocument(
        SourceType sourceType,
        String sourceId,    // путь .adoc-файла или номер issue
        String sourceUrl,   // ссылка для цитаты в ответе
        String title,
        String sourceRef,   // git-тег или дата снимка
        String text         // очищенный текст: именно он хэшируется и режется
) {
    public SourceDocument {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Empty text for " + sourceType + ":" + sourceId);
        }
    }
}