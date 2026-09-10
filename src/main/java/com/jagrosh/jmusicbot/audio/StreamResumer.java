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
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * On startup, queues the live stream each guild was playing when the bot went down and
 * rejoins the voice channel it was in. Entries come from {@link StreamResumeStore}.
 * <p>
 * A stream whose server is unreachable at startup is retried a few times with a delay before
 * being left for the next restart; a stream that no longer resolves at all is forgotten.
 *
 * @author Cohen Singh
 */
public class StreamResumer
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamResumer.class);
    public static final int MAX_LOAD_ATTEMPTS = 5;
    public static final long RETRY_DELAY_SECONDS = 15;

    private final Bot bot;

    public StreamResumer(Bot bot)
    {
        this.bot = bot;
    }

    /** Resumes every remembered stream. Safe to call when the feature is disabled. */
    public void resumeAll(JDA jda)
    {
        StreamResumeStore store = bot.getStreamResumeStore();
        if (store == null || !store.isEnabled())
            return;
        for (StreamResumeStore.Entry entry : store.entries())
        {
            try
            {
                resume(jda, entry, 1);
            }
            catch (RuntimeException e)
            {
                LOG.warn("Failed to resume stream {} for guild {}: {}", entry.uri(), entry.guildId(), e.toString());
            }
        }
    }

    /**
     * Resumes one remembered stream. {@code attempt} counts load attempts for the retry limit.
     */
    public void resume(JDA jda, StreamResumeStore.Entry entry, int attempt)
    {
        StreamResumeStore store = bot.getStreamResumeStore();
        Guild guild = jda.getGuildById(entry.guildId());
        if (guild == null)
        {
            LOG.info("Not resuming stream {}: bot is no longer in guild {}", entry.uri(), entry.guildId());
            store.clear(entry.guildId());
            return;
        }

        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        if (handler.getPlayer().getPlayingTrack() != null)
        {
            LOG.info("Not resuming stream {} in {}: something is already playing", entry.uri(), guild.getName());
            return;
        }

        AudioChannel channel = resolveChannel(guild, entry);
        if (channel == null)
        {
            LOG.warn("Not resuming stream {} in {}: voice channel {} no longer exists and no default voice channel is set",
                    entry.uri(), guild.getName(), entry.voiceChannelId());
            store.clear(entry.guildId());
            return;
        }

        LOG.info("Resuming stream {} in {} / #{} (attempt {}/{})",
                entry.uri(), guild.getName(), channel.getName(), attempt, MAX_LOAD_ATTEMPTS);

        bot.getPlayerManager().loadItemOrdered(guild, entry.uri(), new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack track)
            {
                start(guild, handler, channel, track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                AudioTrack track = playlist.getSelectedTrack() != null
                        ? playlist.getSelectedTrack()
                        : playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0);
                if (track == null)
                    noMatches();
                else
                    start(guild, handler, channel, track);
            }

            @Override
            public void noMatches()
            {
                LOG.warn("Stream {} no longer resolves to anything; forgetting it for {}", entry.uri(), guild.getName());
                store.clear(entry.guildId());
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                if (attempt < MAX_LOAD_ATTEMPTS)
                {
                    LOG.warn("Failed to load stream {} for {} ({}); retrying in {}s",
                            entry.uri(), guild.getName(), exception.getMessage(), RETRY_DELAY_SECONDS);
                    bot.getThreadpool().schedule(() -> resume(jda, entry, attempt + 1), RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
                }
                else
                {
                    LOG.error("Giving up resuming stream {} for {} after {} attempts: {}. It will be tried again on the next restart.",
                            entry.uri(), guild.getName(), MAX_LOAD_ATTEMPTS, exception.getMessage());
                }
            }
        });
    }

    private void start(Guild guild, AudioHandler handler, AudioChannel channel, AudioTrack track)
    {
        if (handler.getPlayer().getPlayingTrack() != null)
        {
            LOG.info("Stream {} loaded but {} started playing something else meanwhile; not resuming", track.getInfo().uri, guild.getName());
            return;
        }
        handler.setLastReason("Resumed after restart.");
        handler.addTrack(new QueuedTrack(track, RequestMetadata.EMPTY));
        guild.getAudioManager().openAudioConnection(channel);
        LOG.info("Resumed stream {} in {} / #{}", track.getInfo().uri, guild.getName(), channel.getName());
    }

    private AudioChannel resolveChannel(Guild guild, StreamResumeStore.Entry entry)
    {
        AudioChannel channel = entry.voiceChannelId() != 0L
                ? guild.getChannelById(AudioChannel.class, entry.voiceChannelId())
                : null;
        if (channel == null)
            channel = bot.getSettingsManager().getSettings(guild).getVoiceChannel(guild);
        return channel;
    }
}
