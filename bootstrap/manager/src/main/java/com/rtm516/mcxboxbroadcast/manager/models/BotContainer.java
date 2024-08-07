package com.rtm516.mcxboxbroadcast.manager.models;

import com.google.common.collect.EvictingQueue;
import com.rtm516.mcxboxbroadcast.core.FriendManager;
import com.rtm516.mcxboxbroadcast.core.SessionManager;
import com.rtm516.mcxboxbroadcast.core.configs.FriendSyncConfig;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import com.rtm516.mcxboxbroadcast.manager.BotManager;
import com.rtm516.mcxboxbroadcast.manager.database.model.Bot;
import com.rtm516.mcxboxbroadcast.manager.models.response.BotInfoResponse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

public class BotContainer {
    private final Bot bot;
    private final EvictingQueue<String> logQueue;
    private final DateTimeFormatter logTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final BotManager botManager;
    private final StorageManager storageManager;

    private Logger logger;
    private SessionManager sessionManager;
    private Status status;
    private int friendCount;

    public BotContainer(BotManager botManager, Bot bot) {
        this.botManager = botManager;
        this.bot = bot;
        this.storageManager = new MongoStorageManager(this);
        this.logQueue = EvictingQueue.create(botManager.backendManager().config().logSize());

        status = Status.OFFLINE;
    }

    public boolean isRunning() {
        return status == Status.ONLINE;
    }

    /**
     * Get the bot info
     * @return the bot info
     */
    public Bot bot() {
        return bot;
    }

    /**
     * Get the bot logs
     *
     * @return the bot logs
     */
    public String logs() {
        return String.join("\n", logQueue);
    }

    /**
     * Log a message
     *
     * @param level the log level
     * @param message the message
     */
    protected void log(String level, String message) {
        logQueue.add("[" + LocalDateTime.now().format(logTimeFormatter) + " " + level + "]" + message);
    }

    /**
     * Convert the bot info into a api response
     *
     * @return the bot info response
     */
    public BotInfoResponse toResponse() {
        return bot.toResponse(status, friendCount);
    }

    /**
     * Save the bot to the database
     */
    public void save() {
        botManager.botCollection().save(bot);
    }

    /**
     * Start the bot
     */
    public void start() {
        // If the bot is already online, don't start it again
        if (status != Status.OFFLINE) {
            return;
        }

        status = Status.STARTING;
        logger = new Logger(this);
        sessionManager = new SessionManager(storageManager, logger);

        sessionManager.restartCallback(this::restart);
        try {
            sessionManager.init(botManager.serverSessionInfo(bot.serverId()), new FriendSyncConfig(botManager.backendManager().config().updateTime().friend(), true, true));
            status = Status.ONLINE;

            bot.gamertag(sessionManager.getGamertag());
            bot.xid(sessionManager.getXuid());
            save();

            // Force update the friend list so we have it sooner for the UI
            sessionManager.friendManager().get();

            sessionManager.scheduledThread().scheduleWithFixedDelay(this::updateSessionInfo, botManager.backendManager().config().updateTime().session(), botManager.backendManager().config().updateTime().session(), TimeUnit.SECONDS);
            sessionManager.scheduledThread().scheduleWithFixedDelay(this::updateFriendStats, 0, botManager.backendManager().config().updateTime().stats(), TimeUnit.SECONDS);
        } catch (SessionCreationException | SessionUpdateException e) {
            logger.error("Failed to create session", e);
            status = Status.OFFLINE;
        } catch (Exception e) {
            logger.error("An unexpected error occurred", e);
            status = Status.OFFLINE;
        }
    }

    /**
     * Stop the bot
     */
    public void stop() {
        // If the bot is offline, don't try and stop it
        if (status == Status.OFFLINE) {
            return;
        }

        sessionManager.shutdown();
        status = Status.OFFLINE;
    }

    /**
     * Restart the bot
     */
    public void restart() {
        stop();
        start();
    }

    /**
     * Update the session info based on the selected server
     */
    public void updateSessionInfo() {
        // If the bot is not online, don't update the session
        if (status != Status.ONLINE) {
            return;
        }

        try {
            // Update the session
            sessionManager.updateSession(botManager.serverSessionInfo(bot.serverId()));
            sessionManager.logger().info("Updated session!");
        } catch (SessionUpdateException e) {
            sessionManager.logger().error("Failed to update session", e);
        }
    }

    public void updateFriendStats() {
        // If the bot is not online, don't update the session
        if (status != Status.ONLINE) {
            return;
        }

        friendCount = sessionManager.socialSummary().targetFollowingCount();
    }

    /**
     * Dump the session to the current storage manager
     */
    public void dumpSession() {
        if (sessionManager != null) {
            sessionManager.dumpSession();
        }
    }

    /**
     * Get the storage manager
     *
     * @return the storage manager
     */
    public StorageManager storageManager() {
        return storageManager;
    }

    /**
     * Get the friend manager
     *
     * @return the friend manager
     */
    public FriendManager friendManager() {
        return sessionManager.friendManager();
    }

    /**
     * Logger proxy for the bot
     */
    public static class Logger implements com.rtm516.mcxboxbroadcast.core.Logger {
        private final BotContainer botContainer;
        private final String prefixString;

        public Logger(BotContainer botContainer) {
            this(botContainer, "");
        }

        public Logger(BotContainer botContainer, String prefixString) {
            this.botContainer = botContainer;
            this.prefixString = prefixString;
        }

        @Override
        public void info(String message) {
            botContainer.log("INFO", prefix(message));
        }

        @Override
        public void warn(String message) {
            botContainer.log("WARN", prefix(message));
        }

        @Override
        public void error(String message) {
            botContainer.log("ERROR", prefix(message));
        }

        @Override
        public void error(String message, Throwable ex) {
            botContainer.log("ERROR", prefix(message) + "\n" + getStackTrace(ex));
        }

        @Override
        public void debug(String message) {
            botContainer.log("DEBUG", prefix(message));
        }

        @Override
        public com.rtm516.mcxboxbroadcast.core.Logger prefixed(String prefixString) {
            return new Logger(botContainer, prefixString);
        }

        private String prefix(String message) {
            if (prefixString.isEmpty()) {
                return message;
            } else {
                return "[" + prefixString + "] " + message;
            }
        }

        /**
         * Helper to get the stack trace as a string
         *
         * @param ex the exception
         * @return the stack trace as a string
         */
        private static String getStackTrace(Throwable ex) {
            // Create a PrintWriter to write the stack trace to
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            // Write the stack trace to the PrintWriter
            ex.printStackTrace(pw);

            // Close the PrintWriter
            pw.close();

            // Return the stack trace as a string
            return sw.toString();
        }
    }

    public enum Status {
        OFFLINE,
        STARTING,
        ONLINE
    }
}
