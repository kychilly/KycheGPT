package com.Discord.DiscordBot.listeners;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import okhttp3.*;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Calendar;

public class AIListener extends ListenerAdapter {
    // API Configuration
    private final String OPENAI_API_KEY = "YOUR API KEY GOES HERE IN THESE QUOTES";
    private final OkHttpClient httpClient = new OkHttpClient();

    // Hardcoded rate limits (Free Tier: 3 RPM / 200 RPD)
    private static final int MAX_REQUESTS_PER_MINUTE = 3;
    private static final int MAX_REQUESTS_PER_DAY = 200;
    private static final long MIN_INTERVAL_MS = 25000; // 25s between requests

    // Tracking
    private final AtomicInteger dailyRequestCount = new AtomicInteger(0);
    private volatile long lastRequestTime = 0;
    private final Queue<MessageReceivedEvent> requestQueue = new ConcurrentLinkedQueue<>();
    private boolean isProcessingQueue = false;

    public AIListener() {
        resetDailyCounter();
        testApiKey(); // Validate key on startup
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        String content = event.getMessage().getContentRaw();

        if (content.equalsIgnoreCase("!ai usage")) {
            showUsage(event.getChannel());
            return;
        }

        if (content.startsWith("!ai ")) {
            String prompt = content.substring(4).trim();
            if (prompt.isEmpty()) {
                event.getChannel().sendMessage("Please include your question after `!ai`").queue();
                return;
            }
            try {
                requestQueue.add(event);
                processQueue();
            } catch (Exception e) {
                event.getChannel().sendMessage("I AM HAVING A MENTAL BREAKDOWN").queue();
            }
        }
    }

    private void processQueue() {
        if (isProcessingQueue || requestQueue.isEmpty()) return;
        isProcessingQueue = true;

        try {
            MessageReceivedEvent event = requestQueue.poll();

            // Rate limit check
            if (!canMakeRequest()) {
                long waitTime = (MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestTime)) / 1000;
                event.getChannel().sendMessage("⌛ Please wait " + waitTime + " seconds").queue();
                requestQueue.add(event); // Re-add to queue
                return;
            }

            // Process request
            event.getChannel().sendTyping().queue();
            String response = getChatGPTResponse(event.getMessage().getContentRaw().substring(4).trim());
            updateRequestCount();

            // Send response
            if (response.length() > 2000) {
                for (int i = 0; i < response.length(); i += 2000) {
                    event.getChannel().sendMessage(response.substring(i, Math.min(i + 2000, response.length()))).queue();
                }
            } else {
                event.getChannel().sendMessage(response).queue();
            }
        } catch (Exception e) {
            handleError(requestQueue.peek(), e); // Handle error for next in queue
        } finally {
            isProcessingQueue = false;
            if (!requestQueue.isEmpty()) {
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        processQueue();
                    }
                }, 1000);
            }
        }
    }

    private synchronized boolean canMakeRequest() {
        return dailyRequestCount.get() < MAX_REQUESTS_PER_DAY &&
                (System.currentTimeMillis() - lastRequestTime) >= MIN_INTERVAL_MS;
    }

    private synchronized void updateRequestCount() {
        dailyRequestCount.incrementAndGet();
        lastRequestTime = System.currentTimeMillis();
    }

    private String getChatGPTResponse(String prompt) throws IOException {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);

        JsonArray messagesArray = new JsonArray();
        messagesArray.add(message);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "gpt-3.5-turbo");
        requestBody.add("messages", messagesArray);
        requestBody.addProperty("model", "gpt-3.5-turbo-0125"); // Latest model

        requestBody.addProperty("temperature", 0.7);
        requestBody.addProperty("max_tokens", 500);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .post(RequestBody.create(requestBody.toString(), JSON))
                .addHeader("Authorization", "Bearer " + OPENAI_API_KEY)
                .addHeader("OpenAI-Beta", "assistants=v2")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " - " + response.body().string());
            }
            return parseResponse(response.body().string());
        }
    }

    private String parseResponse(String json) {
        return JsonParser.parseString(json)
                .getAsJsonObject()
                .getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }

    private void showUsage(MessageChannel channel) {
        channel.sendMessage(String.format(
                "📊 Usage Stats:\n" +
                        "• Today: %d/%d requests\n" +
                        "• Next available: %.1f seconds\n" +
                        "• Queue: %d",
                dailyRequestCount.get(),
                MAX_REQUESTS_PER_DAY,
                Math.max(0, MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestTime)) / 1000.0,
                requestQueue.size()
        )).queue();
    }

    private void handleError(MessageReceivedEvent event, Exception e) {
        String errorMsg = "❌ Error: " + e.getMessage();
        if (e.getMessage().contains("429")) errorMsg = "⚠️ Rate limited. Try again later";
        if (event != null) event.getChannel().sendMessage(errorMsg).queue();
        e.printStackTrace();
    }

    private void resetDailyCounter() {
        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                dailyRequestCount.set(0);
                System.out.println("Daily counter reset at " + new Date());
            }
        }, getMillisUntilMidnight(), 24 * 60 * 60 * 1000);
    }

    private long getMillisUntilMidnight() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() - System.currentTimeMillis();
    }

    private boolean testApiKey() {
        try {
            Request request = new Request.Builder()
                    .url("https://api.openai.com/v1/models")
                    .head()
                    .addHeader("Authorization", "Bearer " + OPENAI_API_KEY)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("❌ Key test failed: HTTP " + response.code());
                    return false;
                }
                System.out.println("✅ API Key is valid");
                return true;
            }
        } catch (IOException e) {
            System.err.println("🚨 Connection failed: " + e.getMessage());
            return false;
        }
    }
}