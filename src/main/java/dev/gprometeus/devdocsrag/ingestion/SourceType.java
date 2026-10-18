package dev.gprometeus.devdocsrag.ingestion;

/** Document source type. Must match the CHECK constraint on {@code document.source_type}. */
public enum SourceType {
    DOC,
    ISSUE
}