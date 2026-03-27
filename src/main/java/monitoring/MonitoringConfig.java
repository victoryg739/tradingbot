package monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class MonitoringConfig {
    private static final Logger log = LoggerFactory.getLogger(MonitoringConfig.class);

    public final boolean enabled;
    public final int port;
    public final String token;
    public final String telegramBotToken;
    public final String telegramChatId;

    private MonitoringConfig(boolean enabled, int port, String token,
                             String telegramBotToken, String telegramChatId) {
        this.enabled = enabled;
        this.port = port;
        this.token = token;
        this.telegramBotToken = telegramBotToken;
        this.telegramChatId = telegramChatId;
    }

    public static MonitoringConfig load() {
        Properties props = new Properties();
        try (InputStream is = MonitoringConfig.class.getClassLoader()
                .getResourceAsStream("monitoring.properties")) {
            if (is != null) {
                props.load(is);
                log.info("Loaded monitoring config from monitoring.properties");
            } else {
                log.warn("monitoring.properties not found on classpath — monitoring disabled");
            }
        } catch (IOException e) {
            log.warn("Failed to load monitoring.properties: {} — monitoring disabled", e.getMessage());
        }

        boolean enabled = Boolean.parseBoolean(props.getProperty("monitoring.enabled", "false"));
        int port = Integer.parseInt(props.getProperty("monitoring.http.port", "8080"));
        String token = props.getProperty("monitoring.http.token", "");
        String telegramBotToken = props.getProperty("monitoring.telegram.botToken", "");
        String telegramChatId = props.getProperty("monitoring.telegram.chatId", "");

        return new MonitoringConfig(enabled, port, token, telegramBotToken, telegramChatId);
    }
}
