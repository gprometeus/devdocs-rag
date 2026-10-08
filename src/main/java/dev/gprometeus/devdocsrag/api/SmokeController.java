package dev.gprometeus.devdocsrag.api;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/smoke")
public class SmokeController {

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;

    public SmokeController(ChatClient.Builder chatClientBuilder, EmbeddingModel embeddingModel) {
        this.chatClient = chatClientBuilder.build();
        this.embeddingModel = embeddingModel;
    }

    // Проверка чат-модели: задаём вопрос, получаем текст
    @GetMapping("/chat")
    public Map<String, String> chat(@RequestParam String q) {
        String answer = chatClient.prompt().user(q).call().content();
        return Map.of("answer", answer);
    }

    // Проверка модели эмбеддингов: текст -> массив чисел
    @GetMapping("/embed")
    public Map<String, Object> embed(@RequestParam String text) {
        float[] vector = embeddingModel.embed(text);
        return Map.of(
                "dimensions", vector.length,
                "first5", Arrays.copyOf(vector, 5)
        );
    }
}