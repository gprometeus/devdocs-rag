package dev.gprometeus.devdocsrag.ingestion.chunking;

/** Kind of a structural unit inside a section; the chunker never splits CODE and TABLE in the middle of a line. */
public enum BlockType { PARAGRAPH, CODE, TABLE }
