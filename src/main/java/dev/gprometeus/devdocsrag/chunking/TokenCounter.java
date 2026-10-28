package dev.gprometeus.devdocsrag.chunking;

/** Counts tokens the way the embedding model sees them, so chunk limits match the model's real input. */
public interface TokenCounter {

    /** Number of tokens in {@code text}, without special tokens and without any truncation. */
    int count(String text);
}
