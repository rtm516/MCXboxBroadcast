package com.rtm516.mcxboxbroadcast.core;

import com.google.gson.JsonParseException;
import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionRequest;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionResponse;
import com.rtm516.mcxboxbroadcast.core.models.session.member.SessionMember;
import com.rtm516.mcxboxbroadcast.core.notifications.NotificationManager;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import org.java_websocket.util.NamedThreadFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple manager to authenticate and create sessions on Xbox
 */
public class SessionManager extends SessionManagerCore {
    private final ScheduledExecutorService scheduledThreadPool;
    private final Map<String, SubSessionManager> subSessionManagers;

    private CoreConfig.FriendSyncConfig friendSyncConfig;
    private Runnable restartCallback;

    private Map<String, String> nonces;

    /**
     * Create an instance of SessionManager
     *
     * @param storageManager The storage manager to use for storing data
     * @param notificationManager The notification manager to use for sending messages
     * @param logger The logger to use for outputting messages
     */
    public SessionManager(StorageManager storageManager, NotificationManager notificationManager, Logger logger) {
        super(storageManager, notificationManager, logger.prefixed("Primary Session"));
        this.scheduledThreadPool = Executors.newScheduledThreadPool(5, new NamedThreadFactory("MCXboxBroadcast Thread"));
        this.subSessionManagers = new HashMap<>();
        this.nonces = new HashMap<>();
    }

    @Override
    public ScheduledExecutorService scheduledThread() {
        return scheduledThreadPool;
    }

    @Override
    public String getSessionId() {
        return sessionInfo.getSessionId();
    }

    /**
     * Get the current session information
     *
     * @return The current session information
     */
    public ExpandedSessionInfo sessionInfo() {
        return sessionInfo;
    }

    /**
     * Initialize the session manager with the given session information
     *
     * @param sessionInfo      The session information to use
     * @param friendSyncConfig The friend sync configuration to use
     * @throws SessionCreationException If the session failed to create either because it already exists or some other reason
     * @throws SessionUpdateException   If the session data couldn't be set due to some issue
     */
    public boolean init(SessionInfo sessionInfo, CoreConfig.FriendSyncConfig friendSyncConfig) throws SessionCreationException, SessionUpdateException {
        // Set the internal session information based on the session info
        this.sessionInfo = new ExpandedSessionInfo("", "", sessionInfo);

        super.init();

        // If we failed to initialize, don't continue with the rest of the setup
        if (!this.initialized) {
            return this.initialized;
        }

        // Set up the auto friend sync
        this.friendSyncConfig = friendSyncConfig;
        friendManager().init(this.friendSyncConfig);

        // Load sub-sessions from cache
        List<String> subSessions = new ArrayList<>();
        try {
            String subSessionsJson = storageManager().subSessions();
            if (!subSessionsJson.isBlank()) {
                subSessions = Arrays.asList(Constants.GSON.fromJson(subSessionsJson, String[].class));
            }
        } catch (IOException ignored) { }

        // Create the sub-sessions in a new thread so we don't block the main thread
        List<String> finalSubSessions = subSessions;
        scheduledThreadPool.execute(() -> {
            // Create the sub-session manager for each sub-session
            for (String subSession : finalSubSessions) {
                try {
                    SubSessionManager subSessionManager = new SubSessionManager(subSession, this, storageManager().subSession(subSession), notificationManager(), logger);
                    subSessionManager.init();
                    subSessionManager.friendManager().init(this.friendSyncConfig);
                    subSessionManagers.put(subSession, subSessionManager);
                } catch (SessionCreationException | SessionUpdateException e) {
                    logger.error("Failed to create sub-session " + subSession, e);
                    // TODO Retry creation after 30s or so
                }
            }
        });

        return this.initialized;
    }

    @Override
    protected boolean handleFriendship() {
        // Don't do anything as we are the main session
        return false;
    }

    /**
     * Update the current session with new information
     *
     * @param sessionInfo The information to update the session with
     * @throws SessionUpdateException If the update failed
     */
    public void updateSession(SessionInfo sessionInfo) throws SessionUpdateException {
        this.sessionInfo.updateSessionInfo(sessionInfo);
        updateSession();
    }

    @Override
    public void updateNonces() throws SessionUpdateException {
        // Get session
        HttpRequest createSessionRequest = HttpRequest.newBuilder()
            .uri(URI.create(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId())))
            .header("Content-Type", "application/json")
            .header("Authorization", getTokenHeader())
            .header("x-xbl-contract-version", "107")
            .GET()
            .build();

        try {
            HttpResponse<String> createSessionResponse = httpClient.send(createSessionRequest, HttpResponse.BodyHandlers.ofString());
            CreateSessionResponse sessionResponse = Constants.GSON.fromJson(createSessionResponse.body(), CreateSessionResponse.class);

            if (sessionResponse == null) {
                throw new SessionUpdateException("Failed to get session for nonces, joining will not work: sessionResponse is null");
            }

            boolean hasChanges = false;

            // Collect active XUIDs from the session
            Set<String> activeXuids = new HashSet<>();
            for (Map.Entry<String, SessionMember> entry : sessionResponse.members().entrySet()) {
                activeXuids.add(entry.getValue().constants().get("system").xuid());
            }

            // Remove our own xuid
            activeXuids.remove(sessionInfo.getXuid());

            // Remove stale nonces
            hasChanges = nonces.keySet().retainAll(activeXuids);

            for (String xuid : activeXuids) {
                if (!nonces.containsKey(xuid)) {
                    // Generate a nonce
                    byte[] bytes = new byte[8];
                    ThreadLocalRandom.current().nextBytes(bytes);
                    StringBuilder hex = new StringBuilder(16);
                    for (byte b : bytes) {
                        hex.append(String.format("%02x", b));
                    }

                    // Put the nonce
                    nonces.put(xuid, hex.toString());

                    logger.debug("Generated nonce for XUID " + xuid + ": " + hex);

                    hasChanges = true;
                }
            }

            // Only update the session properties if something changed
            if (hasChanges) {
                updateSession();
            }
        } catch (IOException | InterruptedException e) {
            throw new SessionUpdateException("Failed to get session for nonces, joining will not work: " + e.getMessage());
        }
    }

    @Override
    protected void updateSession() throws SessionUpdateException {
        // Make sure the websocket connection is still active
        checkConnection();

        String responseBody = super.updateSessionInternal(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId()), new CreateSessionRequest(this.sessionInfo, nonces));
        try {
            CreateSessionResponse sessionResponse = Constants.GSON.fromJson(responseBody, CreateSessionResponse.class);

            // Restart if we have 28/30 session members
            int players = sessionResponse.members().size();
            if (players >= 28) {
                logger.info("Restarting session due to " + players + "/30 players");
                restart();
            }
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Failed to parse session response: " + e.getMessage());
        }
    }

    /**
     * Stop the current session and close the websocket
     */
    public void shutdown() {
        // Shutdown all sub-sessions
        for (SubSessionManager subSessionManager : subSessionManagers.values()) {
            subSessionManager.shutdown();
        }

        // Shutdown self
        super.shutdown();
        scheduledThreadPool.shutdownNow();
    }

    /**
     * Dump the current and last session responses to json files
     */
    public List<String> dumpSession() {
        List<String> response = new ArrayList<String>();
        try {
            storageManager().lastSessionResponse(lastSessionResponse);
        } catch (IOException e) {
            logger.error("Error dumping last session: " + e.getMessage());
            response.add("Error dumping last session: " + e.getMessage());
        }

        HttpRequest createSessionRequest = HttpRequest.newBuilder()
                .uri(URI.create(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId())))
                .header("Content-Type", "application/json")
                .header("Authorization", getTokenHeader())
                .header("x-xbl-contract-version", "107")
                .GET()
                .build();

        try {
            HttpResponse<String> createSessionResponse = httpClient.send(createSessionRequest, HttpResponse.BodyHandlers.ofString());

            storageManager().currentSessionResponse(createSessionResponse.body());
        } catch (IOException | InterruptedException e) {
            logger.error("Error dumping current session: " + e.getMessage());
            response.add("Error dumping current session: " + e.getMessage());
        }
        return response;
    }

    /**
     * Create a sub-session for the given ID
     *
     * @param id The ID of the sub-session to create
     */
    public List<String> addSubSession(String id) {
        List<String> response = new ArrayList<String>();

        // Make sure we don't already have that ID
        if (subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session already exists with that ID");
            response.add("Sub-session already exists with that ID");
            return response;
        }

        // Create the sub-session manager
        try {
            SubSessionManager subSessionManager = new SubSessionManager(id, this, storageManager().subSession(id), notificationManager(), logger);
            subSessionManager.init();
            subSessionManager.friendManager().init(friendSyncConfig);
            subSessionManagers.put(id, subSessionManager);
        } catch (SessionCreationException | SessionUpdateException e) {
            coreLogger.error("Failed to create sub-session", e);
            response.add("Failed to create sub-session" + e.toString());
            return response;
        }

        // Update the list of sub-sessions
        try {
            storageManager().subSessions(Constants.GSON.toJson(subSessionManagers.keySet()));
        } catch (JsonParseException | IOException e) {
            coreLogger.error("Failed to update sub-session list", e);
            response.add("Failed to update sub-session list" + e.toString());
        }

        return response;
    }

    /**
     * Remove a sub-session for the given ID
     *
     * @param id The ID of the sub-session to remove
     */
    public List<String> removeSubSession(String id) {
        List<String> response = new ArrayList<String>();

        // Make sure we have that ID
        if (!subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session does not exist with that ID");
            response.add("Sub-session does not exist with that ID");
            return response;
        }

        // Remove the sub-session manager
        subSessionManagers.get(id).shutdown();
        subSessionManagers.remove(id);

        // Delete the sub-session cache file
        try {
            storageManager().subSession(id).cleanup();
        } catch (IOException e) {
            coreLogger.error("Failed to delete sub-session cache file", e);
            response.add("Failed to delete sub-session cache file" + e.toString());
        }

        // Update the list of sub-sessions
        try {
            storageManager().subSessions(Constants.GSON.toJson(subSessionManagers.keySet()));
        } catch (JsonParseException | IOException e) {
            coreLogger.error("Failed to update sub-session list", e);
            response.add("Failed to update sub-session list" + e.toString());
        }

        coreLogger.info("Removed sub-session with ID " + id);
        response.add("Removed sub-session with ID " + id);

        return response;
    }

    /**
     * List all sessions and their information
     */
    public List<String>  listSessions() {
        List<String> messages = new ArrayList<>();
        coreLogger.info("Loading status of sessions...");

        messages.add("Primary Session:");
        messages.add(" - Gamertag: " + getGamertag());
        messages.add("   Following: " + socialSummary().targetFollowingCount() + "/" + Constants.MAX_FRIENDS);

        if (!subSessionManagers.isEmpty()) {
            messages.add("Sub-sessions: (" + subSessionManagers.size() + ")");
            for (Map.Entry<String, SubSessionManager> subSession : subSessionManagers.entrySet()) {
                messages.add(" - ID: " + subSession.getKey());
                messages.add("   Gamertag: " + subSession.getValue().getGamertag());
                messages.add("   Following: " + subSession.getValue().socialSummary().targetFollowingCount() + "/" + Constants.MAX_FRIENDS);
            }
        } else {
            messages.add("No sub-sessions");
        }

        for (String message : messages) {
            coreLogger.info(message);
        }
        return messages;
    }

    /**
     * Set the callback to run when the session manager needs to be restarted
     *
     * @param restart The callback to run
     */
    public void restartCallback(Runnable restart) {
        this.restartCallback = restart;
    }

    /**
     * Restart the session manager
     */
    public void restart() {
        if (restartCallback != null) {
            restartCallback.run();
        } else {
            logger.error("No restart callback set");
        }
    }
}
