package com;

import com.Discord.DiscordBot.commands.CommandManager;
import com.Discord.DiscordBot.listeners.AIListener;
import com.Discord.DiscordBot.listeners.StatusListener;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

import javax.security.auth.login.LoginException;

public class DiscordBot {
    private final ShardManager shardManager;
    private final Dotenv config;

    public DiscordBot() throws LoginException {
        config = Dotenv.configure().ignoreIfMissing().load();
        String token = config.get("TOKEN");

        DefaultShardManagerBuilder builder = DefaultShardManagerBuilder.createDefault(token);
        builder.setStatus(OnlineStatus.ONLINE);
        builder.setActivity(Activity.customStatus("Doing 4m+ pages of homework"));
        builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
        builder.enableIntents(GatewayIntent.GUILD_MEMBERS);
        builder.setMemberCachePolicy(MemberCachePolicy.ALL);
        builder.setChunkingFilter(ChunkingFilter.ALL);

        shardManager = builder.build();

        // Register event listeners
        shardManager.addEventListener(new AIListener());
        shardManager.addEventListener(new StatusListener());
        shardManager.addEventListener(new CommandManager());

    }

    public static void main(String[] args) {
        try {
            new DiscordBot();
        } catch (LoginException e) {
            System.out.println("Error: Invalid bot token - check your .env file");
        } catch (Exception e) {
            System.out.println("Error: Bot failed to start");
            e.printStackTrace();
        }
    }
}