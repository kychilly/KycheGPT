package com.Discord.DiscordBot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import okhttp3.*;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class AICommand {

    private static final Dotenv config = Dotenv.configure().ignoreIfMissing().load();
    private static final String GROQ_API_KEY = config.get("GROQ_API_KEY");

    private static final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // Map to track last request times per user
    private static final Map<String, Long> lastUsed = new HashMap<>();
    private static final long COOLDOWN_MILLIS = 5000; // 5 seconds

    public static CommandData getCommandData() {
        return Commands.slash("ask", "Ask KycheGPT a question!")
                .addOption(OptionType.STRING,"prompt", "What do you want to ask KycheGPT?", true);
    }

    public static void execute(SlashCommandInteractionEvent event) {
        String message = event.getOption("prompt").getAsString();
        String userId = event.getMember().getId();
        long now = Instant.now().toEpochMilli();

        if (lastUsed.containsKey(userId)) {
            long last = lastUsed.get(userId);
            long remaining = COOLDOWN_MILLIS - (now - last);
            if (remaining > 0) {
                long seconds = (remaining + 999) / 1000;
                event.reply("⏳ Please wait " + seconds + " more second" + (seconds == 1 ? "" : "s") + " before using me!")
                        .setEphemeral(true)
                        .queue();
                return;
            }
        }

        lastUsed.put(userId, now); // Update usage time

        event.deferReply().queue(); // Acknowledge the interaction first

        new Thread(() -> {
            try {
                String reply = getGroqReply(message);
                sendReplyInChunks(reply, event);
            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("⚠️ Error talking to the AI.").queue();
            }
        }).start();
    }

    private static String getGroqReply(String prompt) throws IOException {
        JsonArray messages = new JsonArray();

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "llama3-70b-8192");
        requestBody.add("messages", messages);

        RequestBody body = RequestBody.create(requestBody.toString(), JSON);
        Request request = new Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + GROQ_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response: " + response.code() + "\n" + response.body().string());
            }

            String responseBody = response.body().string();
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            String reply = json.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
            return changeToKyche(reply);
        }
    }

    private static String changeToKyche(String message) {
        // Normalize lookalikes first
        String normalized = noBypassingBadWords(message);

        // Patterns for bad words (expand as needed)
        String[] badWordsPatterns = {
                "(?i)n[iі1!|][gq][gq][e3][r]",  // ni + lookalikes
                "(?i)sh[iі1!|]t",               // shit + lookalikes
                "(?i)f[uυv][cс][kκ]",           // fuck + lookalikes (u, c, k with Cyrillic)
                "(?i)b[iі1!|]t[ch]",            // bitch + lookalikes
                "(?i)a[s5][s5]hole",            // asshole + lookalikes
                // Add more patterns here
        };

        for (String pattern : badWordsPatterns) {
            normalized = normalized.replaceAll(pattern, "****");
        }

        // Also apply your branding replacements on the original message
        String branded = message
                .replaceAll("(?i)i am (llama|llama 2|llama3|mixtral|mistral|gemini|gpt|groq)[^\\n,.]*",
                        "I am KycheGPT, an AI assistant developed by Kyche")
                .replaceAll("(?i)developed by [^\\n,.]*", "developed by Kyche");

        return branded;
    }

    // Normalize common lookalike characters to latin equivalents
    private static String noBypassingBadWords(String input) {
        return input
                .replace('і', 'i')  // Cyrillic small i
                .replace('І', 'I')  // Cyrillic capital i
                .replace('ѕ', 's')  // Cyrillic small s
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('!', 'i')
                .replace('|', 'i')
                .replace('υ', 'u')  // Greek upsilon
                .replace('с', 'c')  // Cyrillic c
                .replace('κ', 'k')  // Greek kappa
                .replace('е', 'e')  // Cyrillic e
                .replace('v', 'v')  // just to be explicit
                ;
    }

    private static void sendReplyInChunks(String reply, SlashCommandInteractionEvent event) {
        int maxLength = 2000;
        int start = 0;
        boolean firstChunk = true;

        while (start < reply.length()) {
            int end = Math.min(start + maxLength, reply.length());

            // Avoid breaking in the middle of a word
            if (end < reply.length() && reply.charAt(end) != ' ') {
                int lastSpace = reply.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }

            String chunk = reply.substring(start, end).trim();

            if (firstChunk) {
                event.getHook().sendMessage(chunk).queue();
                firstChunk = false;
            } else {
                event.getHook().sendMessage(chunk).queue();
            }

            start = end;
            while (start < reply.length() && reply.charAt(start) == ' ') start++; // skip space
        }
    }
}