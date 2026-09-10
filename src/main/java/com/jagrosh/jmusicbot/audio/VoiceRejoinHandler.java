/*
 * Copyright 2026 Cohen Singh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.Bot;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Puts the bot back into its voice channel when it is disconnected while something is still
 * playing: a voice server outage, a region change, or someone dragging it out.
 * <p>
 * Deliberate departures are recognised by their side effect: every "stop"/"leave" path in the
 * bot stops the player before closing the connection, so a disconnect with nothing playing is
 * left alone. A disconnect while a track is playing (or a live stream is waiting to reconnect)
 * schedules a rejoin after a short delay, limited to a number of attempts per five minutes.
 * <p>
 * Two triggers feed this: the voice-state event for the bot itself, and a periodic watchdog
 * that catches cases where no event arrived (for example a stream that was re-queued into a
 * player whose connection had already gone).
 *
 * @author Cohen Singh
 */
public class VoiceRejoinHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceRejoinHandler.class);
    public static final long WATCHDOG_INTERVAL_SECONDS = 30;
    public static final long ATTEMPT_WINDOW_SECONDS = 300;

    private static final class Attempts
    {
        int count;
        long windowStartMillis;
    }

    private final Bot bot;
    private final boolean enabled;
    private final long delaySeconds;
    private final int maxAttempts;
    private final Map<Long, Long> lastChannel = new ConcurrentHashMap<>();
    private final Map<Long, Attempts> attempts = new ConcurrentHashMap<>();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    public VoiceRejoinHandler(Bot bot)
    {
        this.bot = bot;
        this.enabled = bot.getConfig().rejoinVoiceOnDisconnect();
        this.delaySeconds = bot.getConfig().getVoiceRejoinDelaySeconds();
        this.maxAttempts = bot.getConfig().getVoiceRejoinMaxAttempts();
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    /** Starts the watchdog. No-op when disabled. */
    public void init()
    {
        if (enabled)
            bot.getThreadpool().scheduleWithFixedDelay(this::watchdog, WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Tracks the bot's own channel and reacts when it is disconnected. */
    public void onVoiceUpdate(GuildVoiceUpdateEvent event)
    {
        Member member = event.getMember();
        if (member == null || event.getJDA() == null || event.getJDA().getSelfUser() == null
                || member.getIdLong() != event.getJDA().getSelfUser().getIdLong())
            return;

        Guild guild = event.getGuild();
        if (event.getChannelJoined() != null)
        {
            lastChannel.put(guild.getIdLong(), event.getChannelJoined().getIdLong());
            return;
        }
        if (event.getChannelLeft() != null)
        {
            lastChannel.putIfAbsent(guild.getIdLong(), event.getChannelLeft().getIdLong());
            considerRejoin(guild, event.getChannelLeft().getIdLong(),
                    "disconnected from #" + event.getChannelLeft().getName());
        }
    }

    /** Looks for guilds that are playing but not in voice. Runs on the bot's scheduler. */
    public void watchdog()
    {
        JDA jda = bot.getJDA();
        if (jda == null)
            return;
        for (Guild guild : jda.getGuilds())
        {
            try
            {
                AudioHandler handler = handlerOf(guild);
                if (handler == null || !isPlaying(handler))
                    continue;
                AudioManager audioManager = guild.getAudioManager();
                if (!isDisconnected(audioManager) || selfVoiceChannel(guild) != null)
                    continue;
                Long channelId = lastChannel.get(guild.getIdLong());
                considerRejoin(guild, channelId == null ? 0L : channelId, "found playing but not connected to voice");
            }
            catch (RuntimeException e)
            {
                LOG.debug("Voice watchdog failed for guild {}: {}", guild.getIdLong(), e.toString());
            }
        }
    }

    /**
     * Schedules a rejoin if something is playing and the attempt budget allows it.
     */
    public void considerRejoin(Guild guild, long channelId, String why)
    {
        if (!enabled)
            return;
        long guildId = guild.getIdLong();
        AudioHandler handler = handlerOf(guild);
        if (handler == null || !isPlaying(handler))
        {
            LOG.debug("Not rejoining voice in {}: nothing is playing ({})", guild.getName(), why);
            return;
        }
        if (!pending.add(guildId))
            return; // a rejoin is already on its way

        Attempts state = attempts.computeIfAbsent(guildId, id -> new Attempts());
        int attempt;
        synchronized (state)
        {
            long now = System.currentTimeMillis();
            if (now - state.windowStartMillis > ATTEMPT_WINDOW_SECONDS * 1000L)
            {
                state.count = 0;
                state.windowStartMillis = now;
            }
            if (maxAttempts > 0 && state.count >= maxAttempts)
            {
                pending.remove(guildId);
                LOG.warn("Bot was {} in {} while playing, but the rejoin limit of {} attempts per {} minutes is reached; not rejoining",
                        why, guild.getName(), maxAttempts, ATTEMPT_WINDOW_SECONDS / 60);
                return;
            }
            attempt = ++state.count;
        }

        LOG.warn("Bot was {} in {} while playing; rejoining voice in {}s (attempt {}{})",
                why, guild.getName(), delaySeconds, attempt, maxAttempts > 0 ? "/" + maxAttempts : "");
        bot.getThreadpool().schedule(() -> rejoin(guild, channelId), delaySeconds, TimeUnit.SECONDS);
    }

    private void rejoin(Guild guild, long channelId)
    {
        pending.remove(guild.getIdLong());
        try
        {
            AudioHandler handler = handlerOf(guild);
            if (handler == null || !isPlaying(handler))
            {
                LOG.info("Not rejoining voice in {}: playback stopped meanwhile", guild.getName());
                return;
            }
            AudioManager audioManager = guild.getAudioManager();
            if (!isDisconnected(audioManager))
            {
                LOG.info("Not rejoining voice in {}: already connected again", guild.getName());
                return;
            }
            AudioChannel channel = channelId != 0L ? guild.getChannelById(AudioChannel.class, channelId) : null;
            if (channel == null)
                channel = bot.getSettingsManager().getSettings(guild).getVoiceChannel(guild);
            if (channel == null)
            {
                LOG.warn("Cannot rejoin voice in {}: channel {} is gone and no default voice channel is set", guild.getName(), channelId);
                return;
            }
            audioManager.openAudioConnection(channel);
            LOG.info("Rejoined voice channel #{} in {}", channel.getName(), guild.getName());
        }
        catch (RuntimeException e)
        {
            LOG.warn("Failed to rejoin voice in {}: {}", guild.getName(), e.toString());
        }
    }

    /** Number of rejoin attempts made for a guild in the current window. */
    public int getAttempts(long guildId)
    {
        Attempts state = attempts.get(guildId);
        return state == null ? 0 : state.count;
    }

    private static AudioHandler handlerOf(Guild guild)
    {
        return (AudioHandler) guild.getAudioManager().getSendingHandler();
    }

    private static boolean isPlaying(AudioHandler handler)
    {
        return handler.getPlayer().getPlayingTrack() != null || handler.isStreamReconnectPending();
    }

    /** True when the audio connection is neither up nor on its way up. */
    static boolean isDisconnected(AudioManager audioManager)
    {
        ConnectionStatus status = audioManager.getConnectionStatus();
        if (status == null)
            return !audioManager.isConnected();
        return status == ConnectionStatus.NOT_CONNECTED
                || status.name().startsWith("DISCONNECTED")
                || status.name().startsWith("ERROR");
    }

    private static AudioChannel selfVoiceChannel(Guild guild)
    {
        Member self = guild.getSelfMember();
        if (self == null || self.getVoiceState() == null)
            return null;
        return self.getVoiceState().getChannel();
    }
}
