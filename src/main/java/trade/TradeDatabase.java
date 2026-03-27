package trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TradeDatabase {
    private static final Logger log = LoggerFactory.getLogger(TradeDatabase.class);

    private Connection conn;

    public void init(String dbPath) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        conn.setAutoCommit(true);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS trades (
                    exec_id TEXT PRIMARY KEY,
                    symbol TEXT,
                    strategy TEXT,
                    time TEXT,
                    side TEXT,
                    shares REAL,
                    fill_price REAL,
                    commission REAL,
                    realized_pnl REAL,
                    net_pnl REAL,
                    is_closing_trade INTEGER
                )
                """);
        }
        log.info("TradeDatabase: initialized ({})", dbPath);
    }

    public void insertTrade(TradeRecord trade) {
        if (conn == null) return;
        String sql = "INSERT OR IGNORE INTO trades (exec_id, symbol, strategy, time, side, shares, fill_price, commission, realized_pnl, net_pnl, is_closing_trade) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, trade.getExecId());
            ps.setString(2, trade.getSymbol());
            ps.setString(3, trade.getStrategy());
            ps.setString(4, trade.getTime() != null ? trade.getTime().toString() : null);
            ps.setString(5, trade.getSide());
            ps.setDouble(6, trade.getShares());
            ps.setDouble(7, trade.getFillPrice());
            ps.setDouble(8, trade.getCommission());
            ps.setDouble(9, trade.getRealizedPnL());
            ps.setDouble(10, trade.getNetPnL());
            ps.setInt(11, trade.isClosingTrade() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("TradeDatabase: failed to insert trade execId={}", trade.getExecId(), e);
        }
    }

    public List<TradeRecord> loadAll() {
        List<TradeRecord> trades = new ArrayList<>();
        if (conn == null) return trades;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM trades ORDER BY time")) {
            while (rs.next()) {
                String timeStr = rs.getString("time");
                LocalDateTime time = timeStr != null ? LocalDateTime.parse(timeStr) : null;
                trades.add(TradeRecord.builder()
                        .execId(rs.getString("exec_id"))
                        .symbol(rs.getString("symbol"))
                        .strategy(rs.getString("strategy"))
                        .time(time)
                        .side(rs.getString("side"))
                        .shares(rs.getDouble("shares"))
                        .fillPrice(rs.getDouble("fill_price"))
                        .commission(rs.getDouble("commission"))
                        .realizedPnL(rs.getDouble("realized_pnl"))
                        .netPnL(rs.getDouble("net_pnl"))
                        .isClosingTrade(rs.getInt("is_closing_trade") == 1)
                        .build());
            }
        } catch (SQLException e) {
            log.error("TradeDatabase: failed to load trades", e);
        }
        log.info("TradeDatabase: loaded {} trades from database", trades.size());
        return trades;
    }

    public void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                log.warn("TradeDatabase: error closing connection", e);
            }
        }
    }
}
