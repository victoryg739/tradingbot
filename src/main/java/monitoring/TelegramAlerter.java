package monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class TelegramAlerter {
    private static final Logger log = LoggerFactory.getLogger(TelegramAlerter.class);

    private final String botToken;
    private final String chatId;
    private final HttpClient httpClient;

    public TelegramAlerter(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.httpClient = HttpClient.newHttpClient();
    }

    public void sendAlert(String message) {
        if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank()) {
            return;
        }

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
