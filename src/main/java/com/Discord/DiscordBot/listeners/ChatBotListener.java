package com.Discord.DiscordBot.listeners;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import okhttp3.*;

import java.io.IOException;

public class ChatBotListener extends ListenerAdapter {

    // 1. IMPORTANT: Load your key securely from environment variables, don't hardcode it.
    private final String HF_API_KEY = "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";
    // Using the correct model ID for GPT-2.
    // For better chatbot performance, strongly consider using an instruction-tuned model
    // like "meta-llama/Meta-Llama-3-8B-Instruct" or "mistralai/Mistral-7B-Instruct-v0.3"
    // if you have access to them on Hugging Face's Inference API.
    private static final String HF_API_URL = "https://api-inference.huggingface.co/models/openai-community/gpt2";

    // Reuse a single OkHttpClient instance for better performance.
    private final OkHttpClient httpClient = new OkHttpClient();

    public ChatBotListener() {
        if (HF_API_KEY == null || HF_API_KEY.isEmpty()) {
            System.err.println("❌ FATAL: Hugging Face API key is not set in environment variables (HF_API_KEY).");
            // Optionally, you could prevent the bot from starting up fully here.
        } else {
            System.out.println("✅ ChatBotListener initialized successfully.");
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignore messages from bots
        if (event.getAuthor().isBot()) return;

        String content = event.getMessage().getContentRaw();
        // Check if the message starts with the command prefix
        if (content.startsWith("!chat ")) {
            String prompt = content.substring(6).trim();
            event.getChannel().sendTyping().queue();

            try {
                String aiResponse = getAIResponse(prompt);
                event.getChannel().sendMessage(aiResponse).queue();
            } catch (IOException e) {
                event.getChannel().sendMessage("❌ An error occurred while contacting the AI service.").queue();
                e.printStackTrace();
            }
        }
    }

    /**
     * Sends a prompt to the Hugging Face API and returns the generated text.
     * This method is no longer static so it can access instance variables.
     * @param prompt The text prompt to send to the model.
     * @return The AI-generated text response.
     * @throws IOException if there is a network issue.
     */
    public String getAIResponse(String prompt) throws IOException {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        // Create the JSON payload
        JsonObject jsonPayload = new JsonObject();
        jsonPayload.addProperty("inputs", prompt);

        // Add a "parameters" object to the payload for more explicit control over generation.
        JsonObject parameters = new JsonObject();
        parameters.addProperty("max_new_tokens", 100); // Generate up to 100 new tokens
        parameters.addProperty("return_full_text", false); // Only return the generated text, not the prompt + generated text
        // You can add more parameters here like "temperature", "top_k", "top_p" for creative control.
        // For example: parameters.addProperty("temperature", 0.7);
        // For example: parameters.addProperty("do_sample", true); // Enable sampling for more varied responses

        jsonPayload.add("parameters", parameters);

        // *** CHANGE MADE HERE ***
        // Add an "options" object to the payload. This can help with cold starts or specific API routing.
        JsonObject options = new JsonObject();
        options.addProperty("wait_for_model", true); // Wait for the model to be loaded if it's not ready
        options.addProperty("use_cache", true);      // Use cached responses if available
        jsonPayload.add("options", options);


        // Build the request
        RequestBody body = RequestBody.create(jsonPayload.toString(), JSON);
        Request request = new Request.Builder()
                .url(HF_API_URL)
                .post(body)
                .addHeader("Authorization", "Bearer " + HF_API_KEY)
                .build();

        // Execute the request and handle the response
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                // Try to parse an error from the HF API response body
                try {
                    JsonObject errorJson = JsonParser.parseString(responseBody).getAsJsonObject();
                    if (errorJson.has("error")) {
                        return "API Error: " + response.code() + ": " + errorJson.get("error").getAsString();
                    }
                } catch (Exception ignored) {
                    // Fallback if the body isn't a JSON error
                }
                return "Error: HTTP " + response.code() + " - " + response.message() + "\nResponse Body: " + responseBody;
            }

            // 2. Correctly parse the JSON response to get the generated text
            // The API returns an array: [{"generated_text": "..."}]
            JsonElement jsonElement = JsonParser.parseString(responseBody);
            if (jsonElement.isJsonArray()) {
                JsonArray jsonArray = jsonElement.getAsJsonArray();
                if (!jsonArray.isEmpty()) {
                    JsonObject result = jsonArray.get(0).getAsJsonObject();
                    if (result.has("generated_text")) {
                        // Return only the generated text string
                        return result.get("generated_text").getAsString();
                    }
                }
            }
            // Fallback message if the response format is unexpected
            return "Error: Could not parse the AI response.";
        }
    }
}
