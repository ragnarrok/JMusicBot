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

import java.time.Instant;
import java.util.Objects;

/**
 * What a radio station reports it is playing right now.
 *
 * @param stationName the station's display name, or null
 * @param title       current song title, or null
 * @param artist      current song artist, or null
 * @param artworkUrl  cover art URL, or null
 * @param listeners   current listener count, or -1 if unknown
 * @param fetchedAt   when this was read from the station
 * @author Cohen Singh
 */
public record StreamMetadata(String stationName, String title, String artist, String artworkUrl, int listeners, Instant fetchedAt)
{
    /** "Artist - Title", just the title, or null when the station reports nothing. */
    public String songLine()
    {
        boolean hasTitle = title != null && !title.isBlank();
        boolean hasArtist = artist != null && !artist.isBlank();
        if (!hasTitle && !hasArtist)
            return null;
        if (!hasArtist)
            return title.trim();
        if (!hasTitle)
            return artist.trim();
        return artist.trim() + " - " + title.trim();
    }

    /**
     * True if the other snapshot describes the same song and station. The listener count and
     * fetch time are ignored so a bare listener change does not count as a new song.
     */
    public boolean sameSong(StreamMetadata other)
    {
        return other != null
                && Objects.equals(stationName, other.stationName)
                && Objects.equals(title, other.title)
                && Objects.equals(artist, other.artist)
                && Objects.equals(artworkUrl, other.artworkUrl);
    }
}
