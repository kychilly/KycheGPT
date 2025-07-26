package com.Discord.DiscordBot.listeners;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class StatusListener extends ListenerAdapter {

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        String message = event.getMessage().getContentRaw();
        if (message.equals("!status")) {
            event.getChannel().sendMessage("HI, IM KYCHEGPT").queue();
        }
    }
}
