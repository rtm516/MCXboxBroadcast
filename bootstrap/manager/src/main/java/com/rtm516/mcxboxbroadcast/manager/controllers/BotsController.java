package com.rtm516.mcxboxbroadcast.manager.controllers;

import com.rtm516.mcxboxbroadcast.manager.BackendManager;
import com.rtm516.mcxboxbroadcast.manager.BotManager;
import com.rtm516.mcxboxbroadcast.manager.ServerManager;
import com.rtm516.mcxboxbroadcast.manager.models.BotContainer;
import com.rtm516.mcxboxbroadcast.manager.models.response.BotInfoResponse;
import com.rtm516.mcxboxbroadcast.manager.models.request.BotUpdateRequest;
import com.rtm516.mcxboxbroadcast.manager.models.response.CustomResponse;
import com.rtm516.mcxboxbroadcast.manager.models.response.ErrorResponse;
import com.rtm516.mcxboxbroadcast.manager.models.response.FriendResponse;
import com.rtm516.mcxboxbroadcast.manager.models.response.SuccessResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController()
@RequestMapping("/api/bots")
public class BotsController {

    private final BotManager botManager;
    private final ServerManager serverManager;
    private final BackendManager backendManager;

    @Autowired
    public BotsController(BotManager botManager, ServerManager serverManager, BackendManager backendManager) {
        this.botManager = botManager;
        this.serverManager = serverManager;
        this.backendManager = backendManager;
    }

    @GetMapping("")
    public List<BotInfoResponse> bots(HttpServletResponse response) {
        response.setStatus(200);
        return botManager.bots().values().stream().map(BotContainer::toResponse).toList();
    }

    @PostMapping("/create")
    public ObjectId create(HttpServletResponse response) {
        response.setStatus(200);
        BotContainer botContainer = botManager.addBot();
        backendManager.scheduledThreadPool().execute(() -> botContainer.start()); // Start the bot in a new thread
        return botContainer.bot()._id();
    }

    @GetMapping("/{botId:[a-z0-9]+}")
    public BotInfoResponse bot(HttpServletResponse response, @PathVariable ObjectId botId) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return null;
        }
        response.setStatus(200);
        return botManager.bots().get(botId).toResponse();
    }

    @PostMapping("/{botId:[a-z0-9]+}")
    public void update(HttpServletResponse response, @PathVariable ObjectId botId, @RequestBody BotUpdateRequest botUpdateRequest) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return;
        }

        if (!serverManager.servers().containsKey(botUpdateRequest.serverId())) {
            response.setStatus(400);
            return;
        }

        // Update the server ID then save the bot to the database
        BotContainer botContainer = botManager.bots().get(botId);
        botContainer.bot().serverId(botUpdateRequest.serverId());
        botContainer.save();

        // Update the session info in a new thread
        backendManager.scheduledThreadPool().execute(botContainer::updateSessionInfo);

        response.setStatus(200);
    }

    @PostMapping("/{botId:[a-z0-9]+}/start")
    public void start(HttpServletResponse response, @PathVariable ObjectId botId) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return;
        }
        botManager.bots().get(botId).start();
        response.setStatus(200);
    }

    @PostMapping("/{botId:[a-z0-9]+}/stop")
    public void stop(HttpServletResponse response, @PathVariable ObjectId botId) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return;
        }
        botManager.bots().get(botId).stop();
        response.setStatus(200);
    }

    @PostMapping("/{botId:[a-z0-9]+}/restart")
    public void restart(HttpServletResponse response, @PathVariable ObjectId botId) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return;
        }
        botManager.bots().get(botId).restart();
        response.setStatus(200);
    }

    @DeleteMapping("/{botId:[a-z0-9]+}")
    public CustomResponse delete(HttpServletResponse response, @PathVariable ObjectId botId) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return new ErrorResponse("Bot not found");
        }

        botManager.deleteBot(botId);
        response.setStatus(200);
        return new SuccessResponse();
    }

    @GetMapping("/{botId:[a-z0-9]+}/logs")
    public String logs(HttpServletResponse response, @PathVariable ObjectId botId) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return "";
        }
        response.setStatus(200);
        return botManager.logs(botId);
    }

    @GetMapping("/{botId:[a-z0-9]+}/session")
    public String session(HttpServletResponse response, @PathVariable ObjectId botId) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return "";
        }

        BotContainer botContainer = botManager.bots().get(botId);

        botContainer.dumpSession();

        String data;
        try {
            data = botContainer.storageManager().currentSessionResponse();
        } catch (IOException e) {
            response.setStatus(500);
            return "";
        }

        response.setStatus(200);
        return data;
    }

    @GetMapping("/{botId:[a-z0-9]+}/friends")
    public List<FriendResponse> friends(HttpServletResponse response, @PathVariable ObjectId botId) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return null;
        }

        BotContainer botContainer = botManager.bots().get(botId);

        if (!botContainer.isRunning()) {
            response.setStatus(400);
            return null;
        }

        response.setStatus(200);
        return botContainer.friendManager().lastFriendCache().stream().map(FriendResponse::new).toList();
    }

    @DeleteMapping("/{botId:[a-z0-9]+}/friends/{xuid:[0-9]{16}}")
    public void deleteFriend(HttpServletResponse response, @PathVariable ObjectId botId, @PathVariable String xuid) {
        if (!botManager.bots().containsKey(botId)) {
            response.setStatus(404);
            return;
        }

        BotContainer botContainer = botManager.bots().get(botId);

        if (!botContainer.isRunning()) {
            response.setStatus(400);
            return;
        }

        botContainer.friendManager().forceUnfollow(xuid);

        response.setStatus(200);
    }
}
