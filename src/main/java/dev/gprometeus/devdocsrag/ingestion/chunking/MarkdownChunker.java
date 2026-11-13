package dev.gprometeus.devdocsrag.ingestion.chunking;

import dev.gprometeus.devdocsrag.ingestion.MarkdownFences;
import dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Kind;
import dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Line;
import dev.gprometeus.devdocsrag.ingestion.SourceDocument;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns a document into chunks: sections from {@link MarkdownStructureParser}, small sibling sections merged,
 * blocks packed greedily up to {@code target-tokens}, oversized blocks split, every chunk prefixed with its path.
 *
 * <p>Token sizes of blocks come from the parser and are counted once; the tokenizer is called again only for
 * text that is new (a heading turned into a block, a piece of a split block, a whole chunk for the final check),
 * so chunking a document stays linear in its length.
 *
 * <p>Stateless singleton: anything that belongs to one document (sourceId for logs, running index)
 * travels through parameters or local variables, never through fields.
 */
@Component
public class MarkdownChunker {

    private static final Logger log = LoggerFactory.getLogger(MarkdownChunker.class);

    static final String PATH_SEPARATOR = " › ";
    static final String BODY_SEPARATOR = "\n\n";
    private static final String LINE_SEPARATOR = "\n";
    private static final String WORD_SEPARATOR = " ";
    // Fallback for a single huge paragraph line
    static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final String INTRO_END = ":";
    // Each character cut aims a bit below the budget, so that most cuts fit on the first count
    private static final double CUT_MARGIN = 0.9;

    private final MarkdownStructureParser parser;
    private final TokenCounter tokenCounter;
    private final ChunkingProperties props;
    // A process-wide metric, not per-load statistics: monotonic and thread-safe by design,
    // unlike the AtomicInteger fields we removed from IncludeResolver
    private final Counter oversizedChunks;

    public MarkdownChunker(MarkdownStructureParser parser, TokenCounter tokenCounter,
                           ChunkingProperties props, MeterRegistry meterRegistry) {
        this.parser = parser;
        this.tokenCounter = tokenCounter;
        this.props = props;
        this.oversizedChunks = Counter.builder("devdocs.chunking.oversized")
                .description("Chunks whose content exceeds max-tokens after assembly")
                .register(meterRegistry);
    }

    /** Chunks in document order, index from 0; never empty, because a valid document always has text. */
    public List<Chunk> chunk(SourceDocument doc) {
        // An issue may have no title; "" lets its "# Issue #N" h1 take the root of the path (see MarkdownStructureParser)
        String title = doc.title() == null ? "" : doc.title();
        String sourceId = doc.sourceId();
        List<Unit> units = mergeSiblings(parser.parse(title, doc.text()));

        List<Chunk> chunks = new ArrayList<>();
        // Small own text of a parent, waiting for the first body of its first child. In a chain of parents
        // ("## A" text, "### B" text, "#### C") it grows: A's text, B's heading line, B's text
        List<Block> carried = List.of();
        List<String> carriedPath = null;   // path of the last parent whose text is carried
        for (Unit unit : units) {
            Section section = unit.section();
            List<Block> own = section.blocks();
            if (unit.parent()) {
                List<Block> candidate = carried.isEmpty() ? own : withHeading(carried, section, carriedPath, own);
                // Carried on while still small; an empty parent ("## Options" right before "### TopK") adds its heading only
                if (!candidate.isEmpty() && (own.isEmpty() || tokens(candidate) < props.minTokens())) {
                    carried = candidate;
                    carriedPath = section.headingPath();
                    continue;
                }
            }
            List<String> bodies = bodies(section.headingPath(), own, sourceId);
            if (!carried.isEmpty()) {
                bodies = glueCarried(carried, carriedPath, section, bodies, chunks, sourceId);
                carried = List.of();
                carriedPath = null;
            }
            addChunks(chunks, section.headingPath(), bodies, sourceId);
        }
        if (!carried.isEmpty()) {
            addChunks(chunks, carriedPath, bodies(carriedPath, carried, sourceId), sourceId);
        }
        if (chunks.isEmpty()) {
            // Only headings, no content blocks: still a document (decided 2026-10-24: stub pages are never dropped)
            String path = breadcrumb(List.of(title));
            chunks.add(newChunk(0, path, prefix(path) + doc.text().strip(), sourceId));
        }
        return List.copyOf(chunks);
    }

    /** Sibling merging alone, without the parent flags. */
    List<Section> mergeSmallSiblings(List<Section> sections) {
        return mergeSiblings(sections).stream().map(Unit::section).toList();
    }

    /**
     * A section after sibling merging, and whether it has children. The flag is taken from the parsed list:
     * after merging, a parent may be followed by a merged group with the same path as its own, which the
     * "longer path" test of {@link #hasChildren} no longer recognizes as a child.
     */
    private record Unit(Section section, boolean parent) {
    }

    /**
     * Merges a leaf section below {@code min-tokens} with the leaf sections that follow it under the same parent,
     * until the merged size reaches {@code min-tokens}. The merged section gets the parent path; the headings of
     * the merged sections become PARAGRAPH blocks, so "### TopP" stays in the text.
     * <p>
     * Never merged: the text before the first heading (it has no heading to keep), a section with children,
     * and a section whose parent path is empty (an h1 that replaced the title has nothing above it).
     */
    private List<Unit> mergeSiblings(List<Section> sections) {
        List<Unit> result = new ArrayList<>(sections.size());
        int i = 0;
        while (i < sections.size()) {
            Section first = sections.get(i);
            if (!isMergeable(sections, i) || tokens(first) >= props.minTokens()) {
                result.add(new Unit(first, hasChildren(sections, i)));
                i++;
                continue;
            }
            List<String> parent = parentPath(first);
            List<Block> blocks = new ArrayList<>();
            int merged = 0;
            int end = i;
            // Take the first section, then siblings while the result is still small
            do {
                Section next = sections.get(end);
                Block heading = headingBlock(next);
                blocks.add(heading);
                blocks.addAll(next.blocks());
                merged += heading.tokens() + tokens(next);
                end++;
            } while (merged < props.minTokens() && end < sections.size()
                    && isMergeable(sections, end) && parentPath(sections.get(end)).equals(parent));

            if (end - i == 1) {
                result.add(new Unit(first, false));   // no sibling to merge with: keep the section and its own path
            } else {
                result.add(new Unit(new Section(parent, parentLevel(result, parent), blocks), false));
            }
            i = end;
        }
        return result;
    }

    /**
     * Rule 1: puts the carried parent text, then the child's heading line, in front of the child's first body,
     * if the whole content still fits max-tokens; counted on the real content string, since this is the chunk
     * as it will be stored. Otherwise the carried text becomes chunks of its own, under the parent path, and
     * the child's bodies stay as they were: no attempt to re-pack or to cut a split piece further.
     *
     * @return the child's bodies, the first one possibly with the carried text
     */
    private List<String> glueCarried(List<Block> carried, List<String> carriedPath, Section child,
                                     List<String> bodies, List<Chunk> chunks, String sourceId) {
        if (!bodies.isEmpty()) {
            List<Block> lead = withHeading(carried, child, carriedPath, List.of());
            String first = join(lead) + BODY_SEPARATOR + bodies.getFirst();
            String content = prefix(breadcrumb(child.headingPath())) + first;
            if (tokenCounter.count(content) <= props.maxTokens()) {
                List<String> glued = new ArrayList<>(bodies);
                glued.set(0, first);
                return glued;
            }
        }
        addChunks(chunks, carriedPath, bodies(carriedPath, carried, sourceId), sourceId);
        return bodies;
    }

    /**
     * carried + the heading line of {@code section} + {@code blocks}. A merged group of the parent's children has
     * the parent's own path and already starts with its members' headings: no extra heading line for it.
     */
    private List<Block> withHeading(List<Block> carried, Section section, List<String> carriedPath, List<Block> blocks) {
        List<Block> result = new ArrayList<>(carried);
        if (!section.headingPath().equals(carriedPath)) {
            result.add(headingBlock(section));
        }
        result.addAll(blocks);
        return result;
    }

    /** Bodies of one section: the path takes its share of both limits. */
    private List<String> bodies(List<String> headingPath, List<Block> blocks, String sourceId) {
        if (blocks.isEmpty()) {
            return List.of();   // a parent heading right before its subheading: its path lives on in the children
        }
        int prefixTokens = prefixTokens(headingPath);
        // Without this, chunks with long paths (upgrade-notes) would end up systematically above target.
        // The floor keeps a path longer than the limit from producing zero or negative budgets;
        // such chunks are reported by the final check
        int budget = Math.max(props.maxTokens() - prefixTokens, props.minTokens());
        int target = Math.min(Math.max(props.targetTokens() - prefixTokens, props.minTokens()), budget);
        return pack(blocks, budget, target, sourceId);
    }

    private void addChunks(List<Chunk> chunks, List<String> headingPath, List<String> bodies, String sourceId) {
        String path = breadcrumb(headingPath);
        String prefix = prefix(path);
        for (String body : bodies) {
            chunks.add(newChunk(chunks.size(), path, prefix + body, sourceId));
        }
    }

    private static String join(List<Block> blocks) {
        return String.join(BODY_SEPARATOR, blocks.stream().map(Block::text).toList());
    }

    /**
     * Greedy packing of one section's blocks into bodies (blocks joined with {@link #BODY_SEPARATOR}).
     * <ul>
     *   <li>a block above {@code budget} is split, and every piece becomes a body of its own;</li>
     *   <li>a body is closed when the next block would push it above {@code target};</li>
     *   <li>the next body starts with an overlap: the last block of the closed one, if it is a small paragraph;</li>
     *   <li>a paragraph ending with ":" right before code or a table moves into the next body with it;</li>
     *   <li>a last body below {@code min-tokens} is appended to the one before it, if both fit {@code budget}.</li>
     * </ul>
     * Every iteration adds the current block to a body, so the loop always makes progress, and a body never
     * consists of the overlap alone.
     *
     * @param budget hard limit for a body: blocks above it go through {@link #splitOversized}
     * @param target soft goal for a body: a new body starts when the next block would cross it
     */
    List<String> pack(List<Block> blocks, int budget, int target, String sourceId) {
        int separatorTokens = tokenCounter.count(BODY_SEPARATOR);
        List<Body> bodies = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        boolean currentStartsWithOverlap = false;
        int currentTokens = 0;

        for (Block block : blocks) {
            if (block.tokens() > budget) {
                Block intro = movableIntro(current, block);
                // The intro rides with the first piece, so all pieces leave room for it: simpler than a
                // separate budget for the first piece, and costs only a few tokens per piece
                int reserve = intro == null ? 0 : intro.tokens() + separatorTokens;
                if (intro != null && reserve > budget / 2) {
                    intro = null;   // a long "intro" is a paragraph in its own right, leave it where it is
                    reserve = 0;
                }
                if (intro != null) {
                    current.removeLast();
                }
                emit(bodies, current, currentStartsWithOverlap);
                List<Block> pieces = splitOversized(block, budget - reserve, sourceId);
                for (int p = 0; p < pieces.size(); p++) {
                    Block piece = pieces.get(p);
                    bodies.add(new Body(p == 0 && intro != null ? List.of(intro, piece) : List.of(piece), false));
                }
                current = new ArrayList<>();
                currentStartsWithOverlap = false;
                currentTokens = 0;
                continue;
            }

            if (!current.isEmpty() && currentTokens + separatorTokens + block.tokens() > target) {
                Block intro = movableIntro(current, block);
                if (intro != null && intro.tokens() + separatorTokens + block.tokens() <= budget) {
                    current.removeLast();
                    emit(bodies, current, currentStartsWithOverlap);   // no-op when the intro was all there was
                    current = new ArrayList<>(List.of(intro));
                    currentStartsWithOverlap = false;
                    currentTokens = intro.tokens();
                } else {
                    Block overlap = overlap(current, block, budget, separatorTokens);
                    emit(bodies, current, currentStartsWithOverlap);
                    current = new ArrayList<>();
                    currentStartsWithOverlap = overlap != null;
                    currentTokens = 0;
                    if (overlap != null) {
                        current.add(overlap);
                        currentTokens = overlap.tokens();
                    }
                }
            }
            currentTokens += (current.isEmpty() ? 0 : separatorTokens) + block.tokens();
            current.add(block);
        }
        emit(bodies, current, currentStartsWithOverlap);
        mergeSmallTail(bodies, budget, separatorTokens);
        return bodies.stream().map(Body::text).toList();
    }

    /**
     * Splits a block above {@code budget} into blocks that fit: by lines, then sentences, then characters.
     * <ul>
     *   <li>CODE/TABLE: every piece is a valid fenced block with the original opening line (the fence may be
     *       longer than ``` and the info string longer than the language) and a matching closing fence;
     *       lines are never trimmed, blank lines are kept: indentation is meaning in YAML and Python;</li>
     *   <li>TABLE: the first row (the header in our converter's output) is repeated in every piece,
     *       otherwise a piece loses the column names;</li>
     *   <li>PARAGRAPH: lines, a line above the budget by sentences, a sentence above it by characters.</li>
     * </ul>
     */
    List<Block> splitOversized(Block block, int budget, String sourceId) {
        if (block.type() == BlockType.PARAGRAPH) {
            List<String> lines = Arrays.asList(block.text().split(LINE_SEPARATOR, -1));
            Function<String, List<String>> bySentences = line -> greedy(
                    Arrays.asList(SENTENCE_END.split(line)), WORD_SEPARATOR, budget,
                    sentence -> splitByCharacters(sentence, budget, sourceId));
            return greedy(lines, LINE_SEPARATOR, budget, bySentences).stream()
                    .map(text -> new Block(BlockType.PARAGRAPH, text, null, tokenCounter.count(text)))
                    .toList();
        }
        return splitFenced(block, budget, sourceId);
    }

    static String breadcrumb(List<String> headingPath) {
        return String.join(PATH_SEPARATOR, headingPath);
    }

    // --- chunk assembly ---------------------------------------------------------------------------------------

    /** "path\n\n", or nothing when the path is blank (an untitled document without headings). */
    private static String prefix(String breadcrumb) {
        return breadcrumb.isBlank() ? "" : breadcrumb + BODY_SEPARATOR;
    }

    /**
     * The final check counts exactly the embedded string: block sizes do not add up to it (the tokenizer merges
     * characters across a join differently, and the separators cost tokens), so only this number is the truth.
     */
    private Chunk newChunk(int index, String section, String content, String sourceId) {
        int tokenCount = tokenCounter.count(content);
        if (tokenCount > props.maxTokens()) {
            oversizedChunks.increment();
            log.warn("Chunk {} of {} has {} tokens, above max-tokens {} (section: {})",
                    index, sourceId, tokenCount, props.maxTokens(), section);
        }
        return new Chunk(index, section, content, tokenCount);
    }

    // --- merging ----------------------------------------------------------------------------------------------

    private static boolean isMergeable(List<Section> sections, int i) {
        Section section = sections.get(i);
        return section.level() > 0 && section.headingPath().size() > 1 && !hasChildren(sections, i);
    }

    /** Sections are in document order, so the children of a section, if any, come right after it. */
    private static boolean hasChildren(List<Section> sections, int i) {
        if (i + 1 >= sections.size()) {
            return false;
        }
        List<String> path = sections.get(i).headingPath();
        List<String> next = sections.get(i + 1).headingPath();
        return next.size() > path.size() && next.subList(0, path.size()).equals(path);
    }

    private static List<String> parentPath(Section section) {
        List<String> path = section.headingPath();
        return path.subList(0, path.size() - 1);
    }

    /**
     * Level of the parent heading: the parent has children, so it is never merged and sits in {@code result}
     * unchanged. Not "child level - 1": levels may skip ("#" then "###"). 0 when the parent is the title.
     */
    private static int parentLevel(List<Unit> result, List<String> parent) {
        for (int i = result.size() - 1; i >= 0; i--) {
            Section section = result.get(i).section();
            if (section.headingPath().equals(parent)) {
                return section.level();
            }
        }
        return 0;
    }

    private static int tokens(Section section) {
        return tokens(section.blocks());
    }

    /** Sum of the block sizes counted by the parser, without separators: no tokenizer call. */
    private static int tokens(List<Block> blocks) {
        int sum = 0;
        for (Block block : blocks) {
            sum += block.tokens();
        }
        return sum;
    }

    /** Tokens the path prefix ("path\n\n") takes from every chunk of a section with this heading path. */
    private int prefixTokens(List<String> headingPath) {
        String prefix = prefix(breadcrumb(headingPath));
        return prefix.isEmpty() ? 0 : tokenCounter.count(prefix);
    }

    private Block headingBlock(Section section) {
        List<String> path = section.headingPath();
        String text = "#".repeat(section.level()) + " " + path.getLast();
        return new Block(BlockType.PARAGRAPH, text, null, tokenCounter.count(text));
    }

    // --- packing ----------------------------------------------------------------------------------------------

    /** A body being built by {@link #pack}: its blocks, and whether the first one is an overlap copy. */
    private record Body(List<Block> blocks, boolean startsWithOverlap) {

        String text() {
            return join(blocks);
        }

        /** The blocks this body adds to the text: without the overlap, which the previous body already ends with. */
        List<Block> ownBlocks() {
            return startsWithOverlap ? blocks.subList(1, blocks.size()) : blocks;
        }
    }

    private static void emit(List<Body> bodies, List<Block> blocks, boolean startsWithOverlap) {
        if (!blocks.isEmpty()) {
            bodies.add(new Body(List.copyOf(blocks), startsWithOverlap));
        }
    }

    /**
     * A last body below {@code min-tokens} is appended to the previous one if together they fit the budget.
     * The limit is {@code budget} (max-tokens minus the path), not {@code target}: the previous body was closed
     * exactly because the next block would push it above target, so a target check would almost never pass;
     * target is a soft goal, max is the real limit, and one chunk a bit above target beats a crumb.
     * The tail's overlap is left out: it is already the end of the previous body.
     */
    private void mergeSmallTail(List<Body> bodies, int budget, int separatorTokens) {
        if (bodies.size() < 2) {
            return;
        }
        Body tail = bodies.getLast();
        Body previous = bodies.get(bodies.size() - 2);
        int tailTokens = tokens(tail.blocks(), separatorTokens);
        int previousTokens = tokens(previous.blocks(), separatorTokens);
        int ownTokens = tokens(tail.ownBlocks(), separatorTokens);
        if (tailTokens < props.minTokens() && previousTokens + separatorTokens + ownTokens <= budget) {
            List<Block> merged = new ArrayList<>(previous.blocks());
            merged.addAll(tail.ownBlocks());
            bodies.set(bodies.size() - 2, new Body(merged, previous.startsWithOverlap()));
            bodies.removeLast();
        }
    }

    /** Size of blocks joined with {@link #BODY_SEPARATOR}, from the sizes the parser counted. */
    private static int tokens(List<Block> blocks, int separatorTokens) {
        return tokens(blocks) + separatorTokens * Math.max(blocks.size() - 1, 0);
    }

    /** "Add the following dependency:" at the end of {@code current}, when the next block is code or a table. */
    private static Block movableIntro(List<Block> current, Block next) {
        if (current.isEmpty() || next.type() == BlockType.PARAGRAPH) {
            return null;
        }
        Block last = current.getLast();
        boolean intro = last.type() == BlockType.PARAGRAPH && last.text().stripTrailing().endsWith(INTRO_END);
        return intro ? last : null;
    }

    /**
     * The last block of the body being closed, repeated at the start of the next one: only a small paragraph
     * (code and tables are not cut into context crumbs), only if the body has more than that block (otherwise
     * the next body would just repeat the whole previous one), and only if it fits together with the next block.
     */
    private Block overlap(List<Block> closing, Block next, int budget, int separatorTokens) {
        if (closing.size() < 2) {
            return null;
        }
        Block last = closing.getLast();
        boolean fits = last.tokens() + separatorTokens + next.tokens() <= budget;
        return last.type() == BlockType.PARAGRAPH && last.tokens() <= props.overlapTokens() && fits ? last : null;
    }

    // --- splitting --------------------------------------------------------------------------------------------

    private List<Block> splitFenced(Block block, int budget, String sourceId) {
        List<Line> lines = MarkdownFences.scan(block.text());
        String opening = lines.getFirst().text();
        // The parser closes every block it emits; this covers a Block built elsewhere
        boolean closed = lines.getLast().kind() == Kind.FENCE_CLOSE;
        String closing = closed ? lines.getLast().text() : MarkdownStructureParser.fenceRun(opening);
        List<String> content = lines.subList(1, closed ? lines.size() - 1 : lines.size()).stream()
                .map(Line::text)
                .toList();

        String header = null;
        if (block.type() == BlockType.TABLE && !content.isEmpty()) {
            header = content.getFirst();
            content = content.subList(1, content.size());
        }
        String head = header == null ? opening : opening + LINE_SEPARATOR + header;
        // The frame of every piece is paid for up front: the text of a piece with empty content
        int frameTokens = tokenCounter.count(head + LINE_SEPARATOR + LINE_SEPARATOR + closing);
        int contentBudget = Math.max(budget - frameTokens, 1);

        List<String> pieces = greedy(content, LINE_SEPARATOR, contentBudget,
                line -> splitByCharacters(line, contentBudget, sourceId));
        log.debug("Split a {} block of {} tokens in {} into {} pieces", block.type(), block.tokens(), sourceId, pieces.size());
        return pieces.stream()
                .map(piece -> head + LINE_SEPARATOR + piece + LINE_SEPARATOR + closing)
                .map(text -> new Block(block.type(), text, block.fenceInfo(), tokenCounter.count(text)))
                .toList();
    }

    /**
     * Joins consecutive units with {@code separator} while the result fits {@code budget}; a unit that does not
     * fit on its own is replaced by the smaller units {@code finer} makes of it. Every unit is counted once.
     */
    private List<String> greedy(List<String> units, String separator, int budget, Function<String, List<String>> finer) {
        int separatorTokens = tokenCounter.count(separator);
        List<String> pieces = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentTokens = 0;
        for (String unit : units) {
            int tokens = tokenCounter.count(unit);
            if (tokens > budget) {
                flush(pieces, current, separator);
                currentTokens = 0;
                pieces.addAll(finer.apply(unit));
                continue;
            }
            if (!current.isEmpty() && currentTokens + separatorTokens + tokens > budget) {
                flush(pieces, current, separator);
                currentTokens = 0;
            }
            currentTokens += (current.isEmpty() ? 0 : separatorTokens) + tokens;
            current.add(unit);
        }
        flush(pieces, current, separator);
        return pieces;
    }

    private static void flush(List<String> pieces, List<String> current, String separator) {
        if (!current.isEmpty()) {
            pieces.add(String.join(separator, current));
            current.clear();
        }
    }

    /**
     * Last resort for one line above the budget (minified JSON, a stack trace on one line): cuts it by characters.
     * The cut is estimated from the line's characters per token and shrunk until it fits, so each piece costs
     * a couple of tokenizer calls instead of one call per character.
     */
    private List<String> splitByCharacters(String text, int budget, String sourceId) {
        log.warn("A line of {} characters in {} does not fit {} tokens, cutting it by characters",
                text.length(), sourceId, budget);
        double charsPerToken = (double) text.length() / Math.max(tokenCounter.count(text), 1);
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int length = Math.max((int) (budget * charsPerToken * CUT_MARGIN), 1);
            int end = cutPoint(text, start, start + length);
            int tokens = tokenCounter.count(text.substring(start, end));
            while (tokens > budget && end - start > 1) {
                int shorter = start + Math.max((int) ((end - start) * CUT_MARGIN * budget / tokens), 1);
                end = cutPoint(text, start, shorter);
                tokens = tokenCounter.count(text.substring(start, end));
            }
            pieces.add(text.substring(start, end));
            start = end;
        }
        return pieces;
    }

    /** {@code end} clamped to the text, moved back off the middle of a surrogate pair, and at least one code point. */
    private static int cutPoint(String text, int start, int end) {
        int cut = Math.min(end, text.length());
        if (cut < text.length() && Character.isLowSurrogate(text.charAt(cut)) && cut - 1 > start) {
            cut--;
        }
        return Math.max(cut, start + Character.charCount(text.codePointAt(start)));
    }
}
