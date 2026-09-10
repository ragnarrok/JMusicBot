/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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

import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.queue.AbstractQueue;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.MessageFormatter;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class AudioHandler extends AudioEventAdapter implements AudioSendHandler 
{
    public final static String PLAY_EMOJI  = "\u25B6"; // ▶
    public final static String PAUSE_EMOJI = "\u23F8"; // ⏸
    public final static String STOP_EMOJI  = "\u23F9"; // ⏹

    private final static Logger LOGGER = LoggerFactory.getLogger(AudioHandler.class);
    /** How long a reconnected stream must play before the consecutive-attempt counter resets. */
    public static final long STREAM_STABLE_PLAYBACK_MS = 30_000L;
    private final List<AudioTrack> defaultQueue = new LinkedList<>();
    private final Set<String> votes = new HashSet<>();
    
    private final PlayerManager manager;
    private final AudioPlayer audioPlayer;
    private final long guildId;
    /** Listener for audio metrics events. Uses NO_OP implementation in no-GUI mode. */
    private final AudioMetricsListener metricsListener;

    private AudioFrame lastFrame;
    private AbstractQueue<QueuedTrack> queue;
    private String lastReason = null;
    private volatile String favoritedTrackUri = null;

    // Live stream persistence: reconnect streams that drop instead of letting the queue end.
    private final StreamReconnectPolicy streamReconnect;
    private final Object reconnectLock = new Object();
    private ScheduledFuture<?> pendingReconnect;
    private int reconnectGeneration;
    /** Set right before the handler stops a stalled stream itself, so the STOPPED end is treated as a drop. */
    private volatile boolean streamRestartRequested;

    protected AudioHandler(PlayerManager manager, Guild guild, AudioPlayer player)
    {
        this.manager = manager;
        this.audioPlayer = player;
        this.guildId = guild.getIdLong();
        this.streamReconnect = StreamReconnectPolicy.fromConfig(manager.getBot().getConfig());
        // Use NO_OP listener in no-GUI mode to avoid memory allocation
        this.metricsListener = manager.getBot().isNoGUI() 
            ? AudioMetricsListener.NO_OP 
            : new PerformanceMetrics(guildId);

        int maxHistorySize = manager.getBot().getConfig().getMaxHistorySize();
        QueueType queueType = manager.getBot().getSettingsManager().getSettings(guildId).getQueueType();
        this.queue = queueType.createInstance(null, maxHistorySize);
    }

    public void setQueueType(QueueType type)
    {
        // History is preserved when switching queue types
        int maxHistorySize = manager.getBot().getConfig().getMaxHistorySize();
        queue = type.createInstance(queue, maxHistorySize);
    }

    public void setLastReason(String reason)
    {
        this.lastReason = reason;
    }

    public int addTrackToFront(QueuedTrack qtrack)
    {
        if(audioPlayer.getPlayingTrack()==null)
        {
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else
        {
            LOGGER.debug("Added track to front of queue: {}", qtrack.getTrack().getInfo().title);
            queue.addAt(0, qtrack);
            return 0;
        }
    }
    
    public int addTrack(QueuedTrack qtrack)
    {
        if(audioPlayer.getPlayingTrack()==null)
        {
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }

        LOGGER.debug("Added track to queue: {}", qtrack.getTrack().getInfo().title);
        return queue.add(qtrack);
    }
    
    /**
     * Gets the playback history from the queue.
     * Most recent tracks are at index 0.
     * 
     * @return A list of previously played tracks
     */
    public List<QueuedTrack> getPreviousTracks()
    {
        return queue.getHistory().getList();
    }

    public AbstractQueue<QueuedTrack> getQueue()
    {
        return queue;
    }
    
    public void stopAndClear()
    {
        LOGGER.debug("Stopping and clearing queue");
        cancelStreamReconnect();
        clearResumeState();
        queue.clearAll();
        defaultQueue.clear();
        audioPlayer.stopTrack();
        //current = null;
    }

    /**
     * Stops playback and clears only active queues while preserving history.
     * Playback history is maintained from track start events.
     */
    public void stopAndClearQueuePreserveHistory()
    {
        LOGGER.debug("Stopping playback and clearing queue (preserving history)");
        cancelStreamReconnect();
        clearResumeState();
        queue.clear();
        defaultQueue.clear();
        audioPlayer.stopTrack();
    }

    /**
     * Remembers a live stream (and the voice channel it plays in) for resume-on-restart, or
     * forgets it when a regular track takes over.
     */
    private void updateResumeState(AudioTrack track)
    {
        StreamResumeStore store = manager.getBot().getStreamResumeStore();
        if (store == null || !store.isEnabled())
            return;
        if (!StreamReconnectPolicy.isLiveStream(track))
        {
            store.clear(guildId);
            return;
        }

        long channelId = 0L;
        JDA jda = manager.getBot().getJDA();
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        if (guild != null)
        {
            AudioManager audioManager = guild.getAudioManager();
            AudioChannel channel = audioManager.getConnectedChannel();
            if (channel == null)
            {
                // Connection may still be opening; the voice state knows the target channel.
                Member self = guild.getSelfMember();
                if (self != null && self.getVoiceState() != null)
                    channel = self.getVoiceState().getChannel();
            }
            if (channel != null)
                channelId = channel.getIdLong();
        }
        store.record(guildId, channelId, track.getInfo().uri, track.getInfo().title);
    }

    private void clearResumeState()
    {
        StreamResumeStore store = manager.getBot().getStreamResumeStore();
        if (store != null)
            store.clear(guildId);
    }

    /** Starts asking the station what it is playing; the now-playing message follows along. */
    private void startStreamMetadata(AudioTrack track)
    {
        StreamMetadataService service = manager.getBot().getStreamMetadataService();
        if (service == null || !StreamReconnectPolicy.isLiveStream(track))
            return;
        service.start(guildId, track, (gid, t, metadata) ->
                manager.getBot().getNowplayingHandler().onStreamMetadataUpdate(gid, t, metadata));
    }

    private void stopStreamMetadata()
    {
        StreamMetadataService service = manager.getBot().getStreamMetadataService();
        if (service != null)
            service.stop(guildId);
    }

    /** What the station behind the current live stream reports it is playing, or null. */
    public StreamMetadata getStreamMetadata()
    {
        StreamMetadataService service = manager.getBot().getStreamMetadataService();
        return service == null ? null : service.get(guildId);
    }

    /**
     * Whether a dropped live stream is currently waiting to be reconnected.
     */
    public boolean isStreamReconnectPending()
    {
        synchronized (reconnectLock)
        {
            return pendingReconnect != null;
        }
    }

    /**
     * Cancels any scheduled stream reconnect and clears the attempt counter.
     * Called whenever playback is stopped on purpose so a pending reconnect cannot
     * resurrect a stream the user just stopped.
     */
    private void cancelStreamReconnect()
    {
        synchronized (reconnectLock)
        {
            reconnectGeneration++;
            if (pendingReconnect != null)
            {
                pendingReconnect.cancel(false);
                pendingReconnect = null;
            }
        }
        streamRestartRequested = false;
        streamReconnect.reset();
    }

    /**
     * If stream persistence is enabled and the ended track is a live stream that dropped
     * unexpectedly, schedules it to be played again and returns true. Returns false when the
     * normal end-of-track handling (next song, repeat, leave channel) should run instead.
     */
    private boolean scheduleStreamReconnect(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason)
    {
        boolean restartRequested = streamRestartRequested;
        streamRestartRequested = false;

        if (!streamReconnect.isEnabled() || !StreamReconnectPolicy.isLiveStream(track))
            return false;

        if (!streamReconnect.isUnexpectedEnd(endReason, restartRequested))
        {
            // Deliberate stop/skip/replace: start fresh next time the stream drops.
            streamReconnect.reset();
            return false;
        }

        String uri = track.getInfo().uri;
        long delaySeconds = streamReconnect.nextDelaySeconds();
        if (delaySeconds < 0)
        {
            LOGGER.warn("Stream {} dropped ({}) and the reconnect limit of {} attempts was reached; giving up",
                    uri, endReason, streamReconnect.getMaxAttempts());
            streamReconnect.reset();
            return false;
        }

        int attempt = streamReconnect.getAttempts();
        String attemptLabel = streamReconnect.getMaxAttempts() > 0
                ? attempt + "/" + streamReconnect.getMaxAttempts()
                : Integer.toString(attempt);
        LOGGER.warn("Stream {} ended unexpectedly ({}); reconnecting in {}s (attempt {})",
                uri, endReason, delaySeconds, attemptLabel);

        QueuedTrack replacement = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
        lastReason = "Reconnecting to stream (attempt " + attemptLabel + ").";

        Runnable reconnect;
        synchronized (reconnectLock)
        {
            final int generation = ++reconnectGeneration;
            reconnect = () ->
            {
                synchronized (reconnectLock)
                {
                    if (generation != reconnectGeneration)
                        return; // cancelled or superseded
                    pendingReconnect = null;
                }
                if (player.getPlayingTrack() != null)
                {
                    // Someone queued something else in the meantime; don't interrupt it.
                    LOGGER.info("Skipping stream reconnect for {}: another track is already playing", uri);
                    streamReconnect.reset();
                    return;
                }
                LOGGER.info("Reconnecting to stream {}", uri);
                player.playTrack(replacement.getTrack());
            };
            if (delaySeconds > 0)
                pendingReconnect = manager.getBot().getThreadpool().schedule(reconnect, delaySeconds, TimeUnit.SECONDS);
        }
        if (delaySeconds <= 0)
            reconnect.run();
        return true;
    }

    public boolean isMusicPlaying(JDA jda)
    {
        // Check that the selfMember is connected to a channel where they can receive audio
        // Check that the audioPlayer has a playingTrack
        var isBotConnectedToVoice = jda.getGuildById(guildId).getSelfMember().getVoiceState().getChannel() != null;
        var isAudioPlaying = audioPlayer.getPlayingTrack() != null;
        return isBotConnectedToVoice && isAudioPlaying;
    }
    
    public Set<String> getVotes()
    {
        return votes;
    }
    
    public AudioPlayer getPlayer()
    {
        return audioPlayer;
    }
    
    public RequestMetadata getRequestMetadata()
    {
        if(audioPlayer.getPlayingTrack() == null)
            return RequestMetadata.EMPTY;
        RequestMetadata rm = audioPlayer.getPlayingTrack().getUserData(RequestMetadata.class);
        return rm == null ? RequestMetadata.EMPTY : rm;
    }
    
    public boolean playFromDefault()
    {
        if(!defaultQueue.isEmpty())
        {
            audioPlayer.playTrack(defaultQueue.remove(0));
            return true;
        }
        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if(settings==null || settings.getDefaultPlaylist()==null)
            return false;
        
        Playlist pl = manager.getBot().getPlaylistLoader().getPlaylist(settings.getDefaultPlaylist());
        if(pl==null || pl.getItems().isEmpty())
            return false;
        pl.loadTracks(manager, (at) -> 
        {
            if(audioPlayer.getPlayingTrack()==null)
                audioPlayer.playTrack(at);
            else
                defaultQueue.add(at);
        }, () -> 
        {
            if(pl.getTracks().isEmpty() && !manager.getBot().getConfig().getStay())
                manager.getBot().closeAudioConnection(guildId);
        });
        return true;
    }
    
    // Audio Events
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) 
    {
        // Record track end for timeline
        String trackTitle = null;
        String trackUri = null;
        if (track != null && track.getInfo() != null) {
            trackTitle = track.getInfo().title;
            trackUri = track.getInfo().uri;
        }
        metricsListener.onTrackEnd(trackTitle, trackUri);
        stopStreamMetadata();

        // Log track end with details for debugging
        if (endReason != AudioTrackEndReason.FINISHED) {
            LOGGER.debug("Track {} ended with reason: {} (Track: {})", 
                    track != null ? track.getIdentifier() : "null",
                    endReason.name(),
                    trackTitle != null ? trackTitle : "N/A");
        }
        else if (track != null && track.getInfo() != null) {
            LOGGER.debug("Track ended: {} Reason: {}", track.getInfo().title, endReason);
        }

        // A persisted live stream that dropped is reconnected instead of advancing the queue.
        if (scheduleStreamReconnect(player, track, endReason))
            return;

        RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
        // if the track ended normally, and we're in repeat mode, re-add it to the queue
        if(endReason==AudioTrackEndReason.FINISHED && repeatMode != RepeatMode.OFF)
        {
            QueuedTrack clone = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
            if(repeatMode == RepeatMode.ALL)
            {
                queue.add(clone);
                lastReason = "Repeating the queue.";
            }
            else
            {
                queue.addAt(0, clone);
                lastReason = "Repeating the song.";
            }
        }
        
        if(queue.isEmpty())
        {
            if(!playFromDefault())
            {
                lastReason = null;
                manager.getBot().getNowplayingHandler().onTrackUpdate(guildId, null);
                clearResumeState();
                if(!manager.getBot().getConfig().getStay())
                    manager.getBot().closeAudioConnection(guildId);
                // unpause, in the case when the player was paused and the track has been skipped.
                // this is to prevent the player being paused next time it's being used.
                player.setPaused(false);
            }
        }
        else if (endReason != AudioTrackEndReason.REPLACED)
        {
            QueuedTrack qt = queue.pull();
            if (lastReason == null || (!lastReason.startsWith("Repeating") && !lastReason.startsWith("Skipped")))
                lastReason = "Playing next song.";
            player.playTrack(qt.getTrack());
        }
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        // Record exception for timeline
        String trackTitle = null;
        String trackUri = null;
        if (track != null && track.getInfo() != null) {
            trackTitle = track.getInfo().title;
            trackUri = track.getInfo().uri;
        }
        metricsListener.onTrackException(trackTitle, trackUri);
        
        // Build detailed error message with track information
        StringBuilder errorDetails = new StringBuilder();
        errorDetails.append("Track exception occurred:\n");
        errorDetails.append("  Track ID: ").append(track.getIdentifier()).append("\n");
        
        AudioTrackInfo info = track.getInfo();
        if (info != null) {
            errorDetails.append("  Title: ").append(info.title != null ? info.title : "N/A").append("\n");
            errorDetails.append("  URI: ").append(info.uri != null ? info.uri : "N/A").append("\n");
            errorDetails.append("  Author: ").append(info.author != null ? info.author : "N/A").append("\n");
            errorDetails.append("  Duration: ").append(info.length > 0 ? info.length + "ms" : "Unknown").append("\n");
            errorDetails.append("  Source: ").append(track.getSourceManager() != null ? track.getSourceManager().getSourceName() : "Unknown").append("\n");
        }
        
        errorDetails.append("  Exception Severity: ").append(exception.severity != null ? exception.severity.name() : "UNKNOWN").append("\n");
        errorDetails.append("  Exception Message: ").append(exception.getMessage() != null ? exception.getMessage() : "N/A").append("\n");
        
        // Log request metadata if available
        RequestMetadata rm = track.getUserData(RequestMetadata.class);
        if (rm != null && rm.user != null) {
            errorDetails.append("  Requested by: ").append(rm.user.username).append(" (ID: ").append(rm.user.id).append(")\n");
        }
        if (rm != null && rm.requestInfo != null) {
            errorDetails.append("  Original query: ").append(rm.requestInfo.query != null ? rm.requestInfo.query : "N/A").append("\n");
        }
        
        // Log root cause if available
        Throwable cause = exception.getCause();
        if (cause != null) {
            errorDetails.append("  Root Cause: ").append(cause.getClass().getSimpleName()).append(" - ").append(cause.getMessage()).append("\n");
            // Log specific error details for common issues
            if (cause instanceof IllegalStateException) {
                errorDetails.append("  IllegalStateException details: ").append(cause.getMessage()).append("\n");
            } else if (cause instanceof com.fasterxml.jackson.core.JsonParseException) {
                com.fasterxml.jackson.core.JsonParseException jsonEx = (com.fasterxml.jackson.core.JsonParseException) cause;
                errorDetails.append("  JSON Parse Error at line ").append(jsonEx.getLocation().getLineNr())
                           .append(", column ").append(jsonEx.getLocation().getColumnNr()).append("\n");
            }
        }
        
        // Special handling for YouTube OAuth errors
        if (exception.getMessage().equals("Sign in to confirm you're not a bot")
            || exception.getMessage().equals("Please sign in")
            || exception.getMessage().equals("This video requires login."))
        {
            LOGGER.error(
                    "Track {} has failed to play: {}. "
                            + "You will need to sign in to Google to play YouTube tracks. "
                            + "More info: https://jmusicbot.com/youtube-oauth2\n{}",
                    track.getIdentifier(),
                    exception.getMessage(),
                    errorDetails.toString()
            );
        }
        else {
            LOGGER.error("Track {} has failed to play\n{}", track.getIdentifier(), errorDetails.toString(), exception);
        }
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track)
    {
        // Access the metadata object
        var info = track.getInfo();

        LOGGER.debug("Track Started Details:");
        LOGGER.debug(" - Title:      {}", info.title);
        LOGGER.debug(" - Author:     {}", info.author);
        LOGGER.debug(" - Duration:   {} ms", info.length);
        LOGGER.debug(" - Identifier: {}", info.identifier);
        LOGGER.debug(" - URI:        {}", info.uri);
        LOGGER.debug(" - Is Stream:  {}", info.isStream);
        LOGGER.debug(" - Source:     {}", track.getSourceManager() != null ? track.getSourceManager().getSourceName() : "unknown");
        LOGGER.debug(" - Player Vol: {}", player.getVolume());
        LOGGER.debug(" - Is Paused:  {}", player.isPaused());
        votes.clear();
        metricsListener.onSessionReset(); // Reset metrics for new track

        // Record track start for timeline and time-to-first-frame tracking
        String trackTitle = null;
        String trackUri = null;
        if (track != null && track.getInfo() != null) {
            trackTitle = track.getInfo().title;
            trackUri = track.getInfo().uri;
            
            LOGGER.debug("Starting track: {} (ID: {}, URI: {}, Source: {})",
                    trackTitle,
                    track.getIdentifier(),
                    trackUri,
                    track.getSourceManager() != null ? track.getSourceManager().getSourceName() : "Unknown");
        }
        metricsListener.onTrackStart(trackTitle, trackUri);
        if (track != null)
        {
            QueuedTrack startedTrack = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
            queue.addToHistory(startedTrack);
            updateResumeState(track);
            startStreamMetadata(track);
        }

        if (lastReason == null)
            lastReason = "Playing next song.";

        manager.getBot().getNowplayingHandler().onTrackUpdate(guildId, track);
    }
    
    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs)
    {
        // Record the stuck event in performance metrics
        String trackTitle = null;
        String trackUri = null;
        
        if (track != null && track.getInfo() != null) {
            trackTitle = track.getInfo().title;
            trackUri = track.getInfo().uri;
        }
        
        metricsListener.onTrackStuck(thresholdMs, trackTitle, trackUri);
        
        // Build detailed log message
        StringBuilder details = new StringBuilder();
        details.append("Track stuck detected (decoder/stream stall):\n");
        details.append("  Threshold exceeded: ").append(thresholdMs).append("ms\n");
        
        if (track != null) {
            details.append("  Track ID: ").append(track.getIdentifier()).append("\n");
            AudioTrackInfo info = track.getInfo();
            if (info != null) {
                details.append("  Title: ").append(info.title != null ? info.title : "N/A").append("\n");
                details.append("  URI: ").append(info.uri != null ? info.uri : "N/A").append("\n");
                details.append("  Position: ").append(track.getPosition()).append("ms / ")
                       .append(info.length).append("ms\n");
                details.append("  Source: ").append(track.getSourceManager() != null 
                    ? track.getSourceManager().getSourceName() : "Unknown").append("\n");
            }
        }
        
        // Log request metadata if available
        RequestMetadata rm = track != null ? track.getUserData(RequestMetadata.class) : null;
        if (rm != null && rm.user != null) {
            details.append("  Requested by: ").append(rm.user.username)
                   .append(" (ID: ").append(rm.user.id).append(")\n");
        }
        
        LOGGER.warn("Track {} is stuck after {}ms\n{}",
            track != null ? track.getIdentifier() : "null",
            thresholdMs,
            details.toString());

        // A persisted live stream that stops delivering data is dead; restart it rather than
        // sitting in silence. Stopping fires onTrackEnd(STOPPED), which sees the restart flag.
        if (streamReconnect.isEnabled() && StreamReconnectPolicy.isLiveStream(track))
        {
            LOGGER.warn("Stream {} stalled for {}ms; restarting it", track.getInfo().uri, thresholdMs);
            streamRestartRequested = true;
            player.stopTrack();
        }
    }

    //
    public NowPlayingInfo getNowPlayingInfo(JDA jda)
    {
        return new NowPlayingInfo(
            audioPlayer.getPlayingTrack(),
            jda.getGuildById(guildId),
            audioPlayer.isPaused(),
            audioPlayer.getVolume(),
            queue.size(),
            queue.getHistory().size(),
            isCurrentTrackFavorited(),
            lastReason,
            getStreamMetadata()
        );
    }

    // Formatting
    public MessageCreateData getNowPlaying(JDA jda)
    {
        if(isMusicPlaying(jda))
            return MessageFormatter.buildNowPlayingMessage(manager.getBot(), getNowPlayingInfo(jda));
        return null;
    }

    public MessageCreateData getNoMusicPlaying(JDA jda)
    {
        return MessageFormatter.buildNoMusicPlayingMessage(manager.getBot(), getNowPlayingInfo(jda));
    }

    public String getStatusEmoji()
    {
        return audioPlayer.isPaused() ? PAUSE_EMOJI : PLAY_EMOJI;
    }

    public void markCurrentTrackFavorited(String uri)
    {
        favoritedTrackUri = uri;
    }

    public boolean isCurrentTrackFavorited()
    {
        AudioTrack currentTrack = audioPlayer.getPlayingTrack();
        if (currentTrack == null || currentTrack.getInfo() == null)
            return false;
        String currentUri = currentTrack.getInfo().uri;
        return currentUri != null && currentUri.equals(favoritedTrackUri);
    }
    
    // Audio Send Handler methods
    @Override
    public boolean canProvide() 
    {
        long startNanos = System.nanoTime();
        lastFrame = audioPlayer.provide();
        long latencyNanos = System.nanoTime() - startNanos;
        
        boolean frameAvailable = lastFrame != null;
        metricsListener.onFrameProvided(frameAvailable, latencyNanos);

        // Once a reconnected stream has played stably for a while, forget past failures so the
        // next drop starts from the shortest delay again.
        if (frameAvailable && streamReconnect.getAttempts() > 0)
        {
            AudioTrack current = audioPlayer.getPlayingTrack();
            if (current != null && current.getPosition() >= STREAM_STABLE_PLAYBACK_MS)
                streamReconnect.reset();
        }

        return frameAvailable;
    }
    
    /**
     * Gets the performance metrics for this audio handler.
     *
     * @return the performance metrics instance, or null if running in no-GUI mode
     */
    public PerformanceMetrics getPerformanceMetrics() {
        return metricsListener instanceof PerformanceMetrics 
            ? (PerformanceMetrics) metricsListener 
            : null;
    }

    @Override
    public ByteBuffer provide20MsAudio() 
    {
        return ByteBuffer.wrap(lastFrame.getData());
    }

    @Override
    public boolean isOpus() 
    {
        return true;
    }
}
