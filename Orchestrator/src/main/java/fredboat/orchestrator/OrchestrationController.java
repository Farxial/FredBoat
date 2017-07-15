/*
 * MIT License
 *
 * Copyright (c) 2017 Frederik Ar. Mikkelsen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package fredboat.orchestrator;

import fredboat.shared.json.ShardReport;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Controller
public class OrchestrationController {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationController.class);

    @ExceptionHandler({IllegalArgumentException.class, JSONException.class})
    void handleIllegalArgumentException(HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.BAD_REQUEST.value());
    }

    // Called when a new container starts. Tells the invoker which shards to start and when
    @GetMapping(value = "/allocate", produces = "application/json")
    @ResponseBody
    String allocate(@RequestParam("key") String key) {
        Allocation alloc = Allocator.INSTANCE.allocate(key);

        JSONObject out = new JSONObject();

        out.put("chunk", alloc.getChunk());
        out.put("assignedStartTime", alloc.getAssignedStartTime());

        log.info("Allocated chunk " + alloc.getChunk() + " to " + key
                + ". Will begin building in " + (alloc.getAssignedStartTime() - System.currentTimeMillis()) + " millis,");

        return out.toString();
    }

    // one coin = one log in
    // boats need to request coins to start/restart JDA shards to avoid getting disconnected by Discord due to ratelimits
    @GetMapping(value = "/shardcoin", produces = "application/json")
    @ResponseBody
    boolean shardCoin(@AuthenticationPrincipal String activeUser) {
        if (activeUser == null) {
            log.error("Can't give a coin to a null user");
            throw new IllegalStateException("Active user is null");
        }
        log.info("User {} requests a coin", activeUser);
        boolean granted = Treasury.requestCoin(activeUser);
        if (granted) {
            log.info("User {} was granted a coin", activeUser);
        } else {
            log.info("User {} was told to get lost", activeUser);
        }
        return granted;
    }

    // Status of the swarm, like total number of guilds. Can also be used manually
    @GetMapping(value = "/status", produces = "application/json")
    @ResponseBody
    String status() {
        // TODO: 4/29/2017
        return "";
    }

    // Post shard statusses and make sure shards are still alive
    @PostMapping(value = "/heartbeat", produces = "application/json")
    @ResponseBody
    void heartbeat(@RequestBody String rawJson) {
        JSONObject json = new JSONObject(rawJson);
        String key = json.getString("key");

        if (key == null || "".equals(key)) {
            throw new IllegalArgumentException("Provided json object is missing a 'key' property.");
        }

        log.info("Heartbeat received from key {} : {}", key, rawJson);
        Allocation allocation = Allocator.INSTANCE.getAllocation(json.getString("key"));

        if (allocation == null) {
            throw new IllegalArgumentException("There is no allocation present for key: " + key);
        }
        allocation.onBeat();
    }

    // Collection of shard reports
    @PostMapping(value = "/stats", produces = "application/json")
    void userStats(@RequestBody String rawJson) {
        JSONObject json = new JSONObject(rawJson);
        Allocation alloc = Allocator.INSTANCE.getAllocation(json.getString("key"));

        List<ShardReport> reports = new ArrayList<>();

        json.getJSONArray("shards").forEach(o -> reports.add(ShardReport.from((JSONObject) o)));

        log.info("Received " + reports.size() + " reports from chunk " + alloc.getChunk());

        alloc.setReports(reports);
    }

}
