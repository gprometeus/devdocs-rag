package dev.gprometeus.devdocsrag.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@Profile("probe")
public class SimilarityProbe implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SimilarityProbe.class);

    private final EmbeddingModel embeddingModel;

    public SimilarityProbe(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(String... args) throws Exception {
        List<String> phrases = List.of("Как настроить таймаут HTTP-клиента в Spring?",
                "How to configure RestClient timeout in Spring Boot",
                "Какой рецепт борща лучший?",
                "Spring HTTP client timeout setting");
        List<float[]> vectors = embeddingModel.embed(phrases);

        // Compute each norm once instead of on every cosine() call
        double[] norms = new double[vectors.size()];
        for (int i = 0; i < vectors.size(); i++) {
            norms[i] = norm(vectors.get(i));
            log.info("Norm [{}]: {}", i, norms[i]);
        }

        for (int i = 0; i < phrases.size(); i++) {
            log.info("[{}] {}", i, phrases.get(i));
        }
        StringBuilder header = new StringBuilder("     ");
        for (int j = 0; j < vectors.size(); j++) {
            header.append(String.format("%8s", "[" + j + "]"));
        }
        log.info("Cosine similarity:\n{}", header);
        for (int i = 0; i < vectors.size(); i++) {
            StringBuilder row = new StringBuilder(String.format("%-5s", "[" + i + "]"));
            for (int j = 0; j < vectors.size(); j++) {
                double similarity = cosine(vectors.get(i), norms[i], vectors.get(j), norms[j]);
                row.append(String.format(Locale.ROOT, "%8.3f", similarity));
            }
            log.info("{}", row);
        }
    }

    static double cosine(float[] a, double normA, float[] b, double normB) {
        if (normA == 0.0 || normB == 0.0) {
            throw new IllegalArgumentException("Cosine is undefined for a zero-norm vector");
        }
        return dot(a, b) / (normA * normB);
    }

    static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths differ: " + a.length + " vs " + b.length);
        }
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    static double norm(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }
}
