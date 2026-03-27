package monitoring;

import ibkr.IBKRConnection;
import ibkr.model.OrderOutput;
import ibkr.model.PositionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trade.TradeJournal;
import trade.TradeRecord;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Polls Telegram for incoming commands and replies with trading data.
 * Supported commands: /status, /positions, /orders, /pnl, /help
 */
public class TelegramCommandHandler implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(TelegramCommandHandler.class);

    private final String botToken;
    private final String chatId;
    private final IBKRConnection ibkrConnection;
    private final TradeJournal tradeJournal;
    private final HttpClient httpClient;

    private volatile boolean running = true;
    private long lastUpdateId = 0;

    private static final Pattern UPDATE_ID = Pattern.compile("\"update_id\"\\s*:\\s*(\\d+)");
    private static final Pattern CHAT_ID   = Pattern.compile("\"chat\"\\s*:\\{[^}]*\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern TEXT       = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");

    public TelegramCommandHandler(String botToken, String chatId,
                                   IBKRConnection ibkrConnection, TradeJournal tradeJournal) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.ibkrConnection = ibkrConnection;
        this.tradeJournal = tradeJournal;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void stop() { running = false; }

    @Override
    public void run() {
        log.info("Telegram command handler started");
        try {
            // First poll: skip processing to discard old messages
            poll(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            log.warn("Initial Telegram poll failed: {}", e.getMessage());
        }

        while (running) {
            try {
                poll(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Telegram poll error: {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Telegram command handler stopped");
    }

    private void poll(boolean skipProcessing) throws InterruptedException {
        try {
            String url = "https://api.telegram.org/bot" + botToken
                    + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=30";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(35))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("getUpdates returned status {}", response.statusCode());
                Thread.sleep(5000);
                return;
            }

            parseAndProcess(response.body(), skipProcessing);

        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (!skipProcessing) log.debug("Poll failed: {}", e.getMessage());
        }
    }

    private void parseAndProcess(String body, boolean skipProcessing) {
        Matcher updateMatcher = UPDATE_ID.matcher(body);
        Matcher chatMatcher   = CHAT_ID.matcher(body);
        Matcher textMatcher   = TEXT.matcher(body);

        while (updateMatcher.find()) {
            long updateId = Long.parseLong(updateMatcher.group(1));
            lastUpdateId = Math.max(lastUpdateId, updateId);

            boolean hasChat = chatMatcher.find();
            boolean hasText = textMatcher.find();

            if (skipProcessing || !hasChat || !hasText) continue;

            String msgChatId = chatMatcher.group(1);
            String text = textMatcher.group(1);

            if (!chatId.equals(msgChatId)) continue;

            String command = text.trim().split("\\s+")[0].toLowerCase();
            // Strip @botname suffix (e.g. /status@MyBot)
            if (command.contains("@")) command = command.substring(0, command.indexOf('@'));

            String reply = handleCommand(command);
            if (reply != null) sendReply(reply);
        }
    }

    // --- Command handlers ---

    private String handleCommand(String command) {
        return switch (command) {
            case "/status"                -> buildStatusReply();
            case "/positions", "/pos"     -> buildPositionsReply();
            case "/orders"                -> buildOrdersReply();
            case "/pnl", "/trades"        -> buildPnLReply();
            case "/help", "/start"        -> buildHelpReply();
            default -> null;
        };
    }

    private String buildStatusReply() {
        IBKRConnection.ConnectionState state = ibkrConnection.getConnectionState();
        String emoji = state == IBKRConnection.ConnectionState.CONNECTED ? "🟢" : "🔴";
        boolean delayed = ibkrConnection.isMarketDataDelayed();

        return String.format(
                "%s <b>Status</b>\n\n" +
                "Connection: <b>%s</b>\n" +
                "Market Data: <b>%s</b>%s",
                emoji, state,
                ibkrConnection.getMarketDataTypeString(),
                delayed ? " ⚠️" : "");
    }

    private String buildPositionsReply() {
        try {
            List<PositionOutput> positions = ibkrConnection.reqPositions()
                    .stream().filter(p -> !p.getPos().isZero())
                    .collect(Collectors.toList());

            if (positions.isEmpty()) return "📊 <b>Positions</b>\n\nNo open positions.";

            StringBuilder sb = new StringBuilder("📊 <b>Positions</b>\n\n");
            for (PositionOutput pos : positions) {
                sb.append(String.format("<b>%s</b>  %s @ $%.2f\n",
                        pos.getContract().symbol(),
                        pos.getPos(),
                        pos.getAvgCost()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ Failed to fetch positions: " + escapeHtml(e.getMessage());
        }
    }

    private String buildOrdersReply() {
        try {
            List<OrderOutput> orders = ibkrConnection.reqAllOpenOrder();

            if (orders.isEmpty()) return "📋 <b>Orders</b>\n\nNo open orders.";

            StringBuilder sb = new StringBuilder("📋 <b>Orders</b>\n\n");
            for (OrderOutput order : orders) {
                String action = order.getOrder().action().name();
                sb.append(String.format("%s <b>%s</b> %s %s (%s)\n",
                        "BUY".equals(action) ? "🟢" : "🔴",
                        order.getContract().symbol(),
                        action,
                        order.getOrder().totalQuantity(),
                        order.getOrderState().status().name()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ Failed to fetch orders: " + escapeHtml(e.getMessage());
        }
    }

    private String buildPnLReply() {
        List<TradeRecord> trades = tradeJournal.getCompletedTrades();
        List<TradeRecord> closingTrades = trades.stream()
                .filter(TradeRecord::isClosingTrade)
                .collect(Collectors.toList());

        if (closingTrades.isEmpty()) return "💰 <b>P&amp;L</b>\n\nNo closed trades.";

        int winners = (int) closingTrades.stream().filter(t -> t.getNetPnL() > 0).count();
        double netPnL = closingTrades.stream().mapToDouble(TradeRecord::getNetPnL).sum();
        double commission = trades.stream().mapToDouble(TradeRecord::getCommission).sum();

        String emoji = netPnL >= 0 ? "📈" : "📉";
        int winPct = (int) (100.0 * winners / closingTrades.size());

        return String.format(
                "%s <b>P&amp;L Summary</b>\n\n" +
                "Closed Trades: <b>%d</b>\n" +
                "Win Rate: <b>%d/%d (%d%%)</b>\n" +
                "Net P&amp;L: <b>%s$%.2f</b>\n" +
                "Commission: <b>-$%.2f</b>",
                emoji,
                closingTrades.size(),
                winners, closingTrades.size(), winPct,
                netPnL >= 0 ? "+" : "-", Math.abs(netPnL),
                Math.abs(commission));
    }

    private String buildHelpReply() {
        return "🤖 <b>Trading Bot Commands</b>\n\n" +
                "/status - Connection &amp; market data status\n" +
                "/positions - Open positions\n" +
                "/orders - Open orders\n" +
                "/pnl - P&amp;L summary\n" +
                "/help - This help message";
    }

    // --- Telegram API ---

    private void sendReply(String message) {
        try {
            String formBody = "chat_id=" + chatId
                    + "&text=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                    + "&parse_mode=HTML";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, ex) -> {
                        if (ex != null) log.warn("Failed to send Telegram reply: {}", ex.getMessage());
                        else if (resp.statusCode() != 200) log.warn("Telegram reply returned {}: {}", resp.statusCode(), resp.body());
                    });
        } catch (Exception e) {
            log.warn("Failed to send Telegram reply: {}", e.getMessage());
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
