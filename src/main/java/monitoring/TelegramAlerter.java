package monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class TelegramAlerter {
    private static final Logger log = LoggerFactory.getLogger(TelegramAlerter.class);
    private static final Duration COOLDOWN = Duration.ofDays(1);

    private final String botToken;
    private final String chatId;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, Instant> lastSent = new ConcurrentHashMap<>();

    public TelegramAlerter(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.httpClient = HttpClient.newHttpClient();
    }

    public void sendAlert(String message) {
        if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        Instant last = lastSent.get(message);
        if (last != null && Duration.between(last, now).compareTo(COOLDOWN) < 0) {
            log.debug("Suppressing duplicate Telegram alert (cooldown): {}", message);
            return;
        }
        lastSent.put(message, now);

        String encodedMsg = URLEncoder.encode(message, StandardCharsets.UTF_8);
        String url = "https://api.telegram.org/bot" + botToken
                + "/sendMessage?chat_id=" + chatId + "&text=" + encodedMsg;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, ex) -> {
                    if (ex != null) {
                        log.warn("Telegram alert failed: {}", ex.getMessage());
                    } else if (resp.statusCode() != 200) {
                        log.warn("Telegram alert returned status {}: {}", resp.statusCode(), resp.body());
                    }
                });
    }
}
