package monitoring;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ibkr.IBKRConnection;
import ibkr.model.PositionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trade.TradeJournal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public class MonitoringServer {
    private static final Logger log = LoggerFactory.getLogger(MonitoringServer.class);

    private final MonitoringConfig config;
    private final IBKRConnection ibkrConnection;
    private final TradeJournal tradeJournal;
    private final TelegramAlerter telegramAlerter;

    private HttpServer httpServer;
    private final long startTime = System.currentTimeMillis();

    private volatile int lastErrorCode = -1;
    private volatile String lastErrorMsg = "";
    private volatile String tradingMode = "UNKNOWN";

    public MonitoringServer(MonitoringConfig config, IBKRConnection ibkrConnection, TradeJournal tradeJournal) {
        this.config = config;
        this.ibkrConnection = ibkrConnection;
        this.tradeJournal = tradeJournal;
        this.telegramAlerter = new TelegramAlerter(config.telegramBotToken, config.telegramChatId);
    }

    public void setTradingMode(String mode) {
        this.tradingMode = mode;
    }

    public void start() throws IOException {
        if (!config.enabled) {
            log.info("Monitoring disabled (monitoring.enabled=false)");
            return;
        }

        httpServer = HttpServer.create(new InetSocketAddress(config.port), 0);
        httpServer.createContext("/api/status", this::handleStatus);
        httpServer.createContext("/api/positions", this::handlePositions);
        httpServer.createContext("/api/trades", this::handleTrades);
        httpServer.setExecutor(Executors.newFixedThreadPool(4));
        httpServer.start();
        log.info("Monitoring HTTP server started on port {}", config.port);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(2);
            log.info("Monitoring HTTP server stopped");
        }
    }

    public void recordError(int code, String msg) {
        this.lastErrorCode = code;
        this.lastErrorMsg = msg != null ? msg : "";
    }

    public void sendAlert(String msg) {
        telegramAlerter.sendAlert(msg);
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (config.token == null || config.token.isBlank()
                || "CHANGE_ME_USE_A_UUID".equals(config.token)) {
            return false;
        }

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.equals("Bearer " + config.token)) {
            return true;
        }

        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.equals("token=" + config.token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!isAuthorized(exchange)) {
            sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }
        long uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000;
        String json = JsonBuilder.statusJson(
                ibkrConnection.getConnectionState(),
                lastErrorCode, lastErrorMsg,
                tradingMode, uptimeSeconds);
        sendResponse(exchange, 200, json);
    }

    private void handlePositions(HttpExchange exchange) throws IOException {
        if (!isAuthorized(exchange)) {
            sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }
        try {
            List<PositionOutput> positions = ibkrConnection.reqPositions();
            sendResponse(exchange, 200, JsonBuilder.positionsJson(positions));
        } catch (TimeoutException e) {
            sendResponse(exchange, 503, "{\"error\":\"Position request timed out\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendResponse(exchange, 503, "{\"error\":\"Request interrupted\"}");
        } catch (ExecutionException e) {
            log.warn("Error fetching positions: {}", e.getMessage());
            sendResponse(exchange, 500, "{\"error\":\"Internal error\"}");
        }
    }

    private void handleTrades(HttpExchange exchange) throws IOException {
        if (!isAuthorized(exchange)) {
            sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }
        String json = JsonBuilder.tradesJson(
                tradeJournal.getCompletedTrades(),
                tradeJournal.getStrategySummaries());
        sendResponse(exchange, 200, json);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
