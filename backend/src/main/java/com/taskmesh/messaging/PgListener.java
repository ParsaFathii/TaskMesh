package com.taskmesh.messaging;

import com.taskmesh.config.TaskMeshProperties;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * Dedicated single-connection LISTEN thread for the {@code taskmesh_events} channel.
 * PostgreSQL delivers NOTIFY payloads to this connection; they are polled every
 * 500&nbsp;ms via {@code PGConnection.getNotifications()} and handed to the
 * {@link EventBroadcaster}. The connection is automatically re-established on failure.
 *
 * <p>This runs on its own JDBC connection outside the Hikari pool because LISTEN
 * requires a permanently idle session.</p>
 */
@Component
public class PgListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PgListener.class);
    private static final Pattern SAFE_CHANNEL = Pattern.compile("[a-z_][a-z0-9_]*");

    private final EventBroadcaster broadcaster;
    private final String url;
    private final String user;
    private final String password;
    private final String channel;
    private final boolean enabled;

    private volatile boolean running;
    private volatile Connection connection;
    private volatile Thread listenerThread;

    public PgListener(EventBroadcaster broadcaster, Environment environment,
                      TaskMeshProperties properties) {
        this.broadcaster = broadcaster;
        this.url = environment.getProperty("spring.datasource.url",
                "jdbc:postgresql://localhost:5433/taskmesh");
        this.user = environment.getProperty("spring.datasource.username", "taskmesh");
        this.password = environment.getProperty("spring.datasource.password", "");
        this.channel = properties.eventChannel();
        this.enabled = properties.listenerEnabled();
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("PostgreSQL event listener disabled by configuration");
            return;
        }
        if (!SAFE_CHANNEL.matcher(channel).matches()) {
            log.warn("Invalid NOTIFY channel name '{}'; listener not started", channel);
            return;
        }
        running = true;
        listenerThread = new Thread(this::listenLoop, "taskmesh-pg-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    @Override
    public void stop() {
        running = false;
        Thread thread = listenerThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeConnection();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listenLoop() {
        while (running) {
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                this.connection = conn;
                try (Statement statement = conn.createStatement()) {
                    statement.execute("LISTEN " + channel);
                }
                log.info("Listening on PostgreSQL channel '{}'", channel);
                pollNotifications(conn);
            } catch (SQLException | InterruptedException e) {
                if (!running) {
                    return;
                }
                log.warn("Event listener connection lost ({}); reconnecting in 2s", e.getMessage());
                sleepQuietly(2000);
            }
        }
    }

    private void pollNotifications(Connection conn) throws SQLException, InterruptedException {
        PGConnection pg = conn.unwrap(PGConnection.class);
        while (running) {
            PGNotification[] notifications = pg.getNotifications(500);
            if (notifications != null) {
                for (PGNotification notification : notifications) {
                    broadcaster.handleNotification(notification.getParameter());
                }
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeConnection() {
        Connection conn = this.connection;
        this.connection = null;
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // Closing a broken connection can fail; nothing to do.
            }
        }
    }
}
