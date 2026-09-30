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
package com.jagrosh.jmusicbot.unit.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.jagrosh.jmusicbot.utils.StreamDiagnosticsLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StreamDiagnosticsLogging Tests")
class StreamDiagnosticsLoggingTest
{
    private static final String STREAM_LOGGER = "com.jagrosh.jmusicbot.audio.StreamDiagnostics";

    @TempDir
    Path dir;

    @AfterEach
    void tearDown()
    {
        StreamDiagnosticsLogging.disable();
    }

    private static Logger logger(String name)
    {
        return (Logger) LoggerFactory.getLogger(name);
    }

    @Test
    @DisplayName("enable() writes log lines to the file with millisecond timestamps and raises stream loggers to DEBUG")
    void enableWritesFileAndRaisesLevels() throws Exception
    {
        Path file = dir.resolve(StreamDiagnosticsLogging.FILE_NAME);
        Level before = logger(STREAM_LOGGER).getLevel();

        StreamDiagnosticsLogging.enable(file);

        assertTrue(StreamDiagnosticsLogging.isEnabled());
        for (String name : StreamDiagnosticsLogging.DEBUG_LOGGERS)
            assertEquals(Level.DEBUG, logger(name).getLevel(), name);

        logger(STREAM_LOGGER).debug("diagnostic marker {}", 42);

        String content = Files.readString(file);
        assertTrue(content.contains("diagnostic marker 42"), content);
        assertTrue(content.matches("(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[DEBUG\\] \\[.+\\] \\[StreamDiagnostics\\]: diagnostic marker 42.*"), content);

        StreamDiagnosticsLogging.disable();
        assertFalse(StreamDiagnosticsLogging.isEnabled());
        assertEquals(before, logger(STREAM_LOGGER).getLevel(), "level restored");
    }

    @Test
    @DisplayName("enable() twice attaches a single appender")
    void enableIsIdempotent() throws Exception
    {
        Path file = dir.resolve(StreamDiagnosticsLogging.FILE_NAME);
        StreamDiagnosticsLogging.enable(file);
        StreamDiagnosticsLogging.enable(file);

        logger(STREAM_LOGGER).info("only once");

        String content = Files.readString(file);
        assertEquals(1, content.split("only once", -1).length - 1, "line must not be duplicated");
    }

    @Test
    @DisplayName("not enabled by default")
    void disabledByDefault()
    {
        assertFalse(StreamDiagnosticsLogging.isEnabled());
    }
}
