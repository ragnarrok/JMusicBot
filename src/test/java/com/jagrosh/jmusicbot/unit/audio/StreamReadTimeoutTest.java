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
package com.jagrosh.jmusicbot.unit.audio;

import com.jagrosh.jmusicbot.audio.AudioSource;
import org.apache.http.client.config.RequestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("HTTP stream read timeout")
class StreamReadTimeoutTest
{
    private static final RequestConfig LAVAPLAYER_DEFAULT = RequestConfig.custom()
            .setConnectTimeout(3000)
            .setSocketTimeout(3000)
            .setCookieSpec("standard")
            .build();

    @Test
    @DisplayName("raises the socket timeout and leaves the other request settings alone")
    void raisesSocketTimeoutOnly()
    {
        RequestConfig out = AudioSource.streamReadTimeoutConfigurator(30).apply(LAVAPLAYER_DEFAULT);

        assertEquals(30_000, out.getSocketTimeout());
        assertEquals(3000, out.getConnectTimeout(), "connect timeout untouched");
        assertEquals("standard", out.getCookieSpec(), "cookie spec untouched");
    }

    @Test
    @DisplayName("values below one second are clamped to one second")
    void clampsToOneSecond()
    {
        assertEquals(1000, AudioSource.streamReadTimeoutConfigurator(0).apply(LAVAPLAYER_DEFAULT).getSocketTimeout());
        assertEquals(1000, AudioSource.streamReadTimeoutConfigurator(-5).apply(LAVAPLAYER_DEFAULT).getSocketTimeout());
    }

    @Test
    @DisplayName("huge values do not overflow the int millisecond field")
    void noOverflow()
    {
        int max = (Integer.MAX_VALUE / 1000) * 1000;
        assertEquals(max, AudioSource.streamReadTimeoutConfigurator(Long.MAX_VALUE / 10).apply(LAVAPLAYER_DEFAULT).getSocketTimeout());
        assertEquals(max, AudioSource.streamReadTimeoutConfigurator(Long.MAX_VALUE).apply(LAVAPLAYER_DEFAULT).getSocketTimeout());
    }
}
