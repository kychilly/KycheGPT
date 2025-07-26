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
    // Hugging Face API Configuration
    private final String HF_API_KEY = "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";
    private final String HF_MODEL = "gpt2"; // supposedly works but it doesnt
    private final String HF_API_URL = "https://api-inference.huggingface.co/models/" + HF_MODEL;

    private final OkHttpClient httpClient = new OkHttpClient();

    // Rate limits (adjust based on your needs)
    private static final int MAX_REQUESTS_PER_MINUTE = 10;
    private static final int MAX_REQUESTS_PER_DAY = 100;
    private static final long MIN_INTERVAL_MS = 6000; // 6s between requests

    // Tracking
    private final AtomicInteger dailyRequestCount = new AtomicInteger(0);
    private volatile long lastRequestTime = 0;
    private final Queue<MessageReceivedEvent> requestQueue = new ConcurrentLinkedQueue<>();
    private boolean isProcessingQueue = false;

    public AIListener() {
        testApiKey();
        try {
            testHuggingFaceAPI();
        } catch (Exception e) {
            System.out.println("im a fucking dumbass");
        }
        resetDailyCounter();
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
                event.getChannel().sendMessage("Error processing your request").queue();
                e.printStackTrace();
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
            String response = getAIResponse(event.getMessage().getContentRaw().substring(4).trim());
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
            handleError(requestQueue.peek(), e);
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

    private String getAIResponse(String prompt) throws IOException {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        String apiUrl = "https://api-inference.huggingface.co/models/" + HF_MODEL;
        System.out.println("API URL: " + apiUrl);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("inputs", prompt);


        JsonObject parameters = new JsonObject();
        parameters.addProperty("max_new_tokens", 100);  // more reliable than `max_length` with modern models
        parameters.addProperty("temperature", 0.7);
        requestBody.add("parameters", parameters);

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(requestBody.toString(), JSON))
                .addHeader("Authorization", "Bearer " + HF_API_KEY)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();

            if (response.code() == 503) {
                return "⏳ The model is still loading. Please try again in 30 seconds.";
            }

            if (!response.isSuccessful()) {
                // Return the raw message if it's not JSON
                return "❌ API Error " + response.code() + ": " + responseBody;
            }

            // Debug log (optional)
            System.out.println("✅ HF Raw Response: " + responseBody);

            return parseHuggingFaceResponse(responseBody);
        }
    }

    private String parseHuggingFaceResponse(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);

            // Case 1: Response is a JSON object with generated_text
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();

                if (obj.has("error")) {
                    return "❌ Hugging Face Error: " + obj.get("error").getAsString();
                }

                if (obj.has("generated_text")) {
                    return cleanAssistantReply(obj.get("generated_text").getAsString());
                }
            }

            // Case 2: Array format (common for many models)
            if (parsed.isJsonArray()) {
                JsonArray array = parsed.getAsJsonArray();
                if (array.size() > 0 && array.get(0).getAsJsonObject().has("generated_text")) {
                    String fullReply = array.get(0).getAsJsonObject().get("generated_text").getAsString();
                    return cleanAssistantReply(fullReply);
                }
            }

            // If format is unexpected
            return "⚠️ Unexpected response format: " + json;

        } catch (JsonSyntaxException e) {
            return "❌ Failed to parse JSON: " + e.getMessage() + "\nRaw: " + json;
        }
    }

    private String cleanAssistantReply(String fullResponse) {
        // Remove everything before "Assistant:", if present
        return fullResponse.replaceFirst("(?s).*?Assistant:\\s*", "").trim();
    }


    private synchronized boolean canMakeRequest() {
        return dailyRequestCount.get() < MAX_REQUESTS_PER_DAY &&
                (System.currentTimeMillis() - lastRequestTime) >= MIN_INTERVAL_MS;
    }

    private synchronized void updateRequestCount() {
        dailyRequestCount.incrementAndGet();
        lastRequestTime = System.currentTimeMillis();
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
            // Test against the whoami endpoint which always works
            Request request = new Request.Builder()
                    .url("https://huggingface.co/api/whoami-v2")
                    .get()
                    .addHeader("Authorization", "Bearer " + HF_API_KEY)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    System.out.println("✅ Key valid! Account info: " + body);
                    return true;
                }
                System.err.println("❌ Key test failed. HTTP " + response.code() +
                        "\nFull response: " + response.body().string());
                return false;
            }
        } catch (IOException e) {
            System.err.println("🚨 Connection failed: " + e.getMessage());
            return false;
        }
    }

    // test this fuckass bot
    public static void testHuggingFaceAPI() throws IOException {
        String apiUrl = "https://api-inference.huggingface.co/models/gpt2";
        String json = "{\"inputs\":\"Hello\"}";  // Simple input

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(json, MediaType.get("application/json")))
                .addHeader("Authorization", "Bearer YOUR_KEY")
                .build();

        try (Response response = new OkHttpClient().newCall(request).execute()) {
            System.out.println("Response: " + response.body().string());
        }
    }

}