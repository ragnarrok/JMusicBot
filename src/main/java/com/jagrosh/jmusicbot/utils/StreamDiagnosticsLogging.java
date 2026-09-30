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
package com.jagrosh.jmusicbot.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Switches on the logging needed to diagnose live-stream stutters.
 * <p>
 * When enabled, everything the console shows is also written to {@value #FILE_NAME} with
 * millisecond timestamps and thread names (the console pattern only has whole seconds), and the
 * loggers that matter for a stream are raised to DEBUG: the bot's own stream code, lavaplayer's
 * HTTP reader, MP3 container and track executor, and JDA's voice and gateway connections. The
 * file rolls at 10 MB and keeps three old files, so it can be left on for days.
 *
 * @author Cohen Singh
 */
public final class StreamDiagnosticsLogging
{
    public static final String FILE_NAME = "stream-diagnostics.log";
    public static final String APPENDER_NAME = "StreamDiagnosticsFile";

    /** Loggers raised to DEBUG while diagnostics are on. */
    public static final List<String> DEBUG_LOGGERS = List.of(
            "com.jagrosh.jmusicbot.audio.StreamDiagnostics",
            "com.jagrosh.jmusicbot.audio.StreamMetadataService",
            "com.jagrosh.jmusicbot.audio.AudioHandler",
            "com.jagrosh.jmusicbot.audio.VoiceRejoinHandler",
            "com.sedmelluq.discord.lavaplayer.tools.io",
            "com.sedmelluq.discord.lavaplayer.source.http",
            "com.sedmelluq.discord.lavaplayer.container.mp3",
            "com.sedmelluq.discord.lavaplayer.track.playback",
            "net.dv8tion.jda.internal.audio",
            "net.dv8tion.jda.internal.requests.WebSocketClient");

    private static final Map<String, Level> previousLevels = new LinkedHashMap<>();

    private StreamDiagnosticsLogging() {}

    /**
     * Enables diagnostics logging to the given file. Calling it again is a no-op.
     */
    public static synchronized void enable(Path file)
    {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) != null)
            return;

        String path = file.toAbsolutePath().toString();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%level] [%thread] [%logger{0}]: %msg%n%ex");
        encoder.start();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.setFile(path);
        appender.setAppend(true);
        appender.setEncoder(encoder);

        FixedWindowRollingPolicy rolling = new FixedWindowRollingPolicy();
        rolling.setContext(context);
        rolling.setParent(appender);
        rolling.setFileNamePattern(path + ".%i");
        rolling.setMinIndex(1);
        rolling.setMaxIndex(3);
        rolling.start();

        SizeBasedTriggeringPolicy<ILoggingEvent> trigger = new SizeBasedTriggeringPolicy<>();
        trigger.setContext(context);
        trigger.setMaxFileSize(FileSize.valueOf("10MB"));
        trigger.start();

        appender.setRollingPolicy(rolling);
        appender.setTriggeringPolicy(trigger);
        appender.start();
        root.addAppender(appender);

        for (String name : DEBUG_LOGGERS)
        {
            Logger logger = context.getLogger(name);
            previousLevels.put(name, logger.getLevel());
            logger.setLevel(Level.DEBUG);
        }
    }

    /** Whether diagnostics logging is currently enabled. */
    public static synchronized boolean isEnabled()
    {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender(APPENDER_NAME) != null;
    }

    /** Removes the file appender and restores the logger levels. Used by tests. */
    public static synchronized void disable()
    {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = root.getAppender(APPENDER_NAME);
        if (appender != null)
        {
            root.detachAppender(appender);
            appender.stop();
        }
        previousLevels.forEach((name, level) -> context.getLogger(name).setLevel(level));
        previousLevels.clear();
    }
}
