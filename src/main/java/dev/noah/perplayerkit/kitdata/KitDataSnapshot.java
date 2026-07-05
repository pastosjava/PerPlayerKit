/*
 * Copyright 2022-2025 Noah Ross
 *
 * This file is part of PerPlayerKit.
 *
 * PerPlayerKit is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * PerPlayerKit is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PerPlayerKit. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.noah.perplayerkit.kitdata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * In-memory contents of a kit data export: kit room pages, public kits
 * (including their config metadata) and per-player kit/ender chest entries.
 * All item data is carried as the same serialized Base64 blob strings the
 * storage backends use, so entries are copied verbatim between database and
 * file.
 */
public class KitDataSnapshot {

    /**
     * A public kit with its config metadata. {@code data} is the serialized
     * kit blob and may be null for kits that are declared in the config but
     * have never been saved with /savepublickit.
     */
    public record PublicKitEntry(String id, String name, String icon, String data) {
    }

    private final SortedMap<Integer, String> kitRoomPages = new TreeMap<>();
    private final Map<String, PublicKitEntry> publicKits = new LinkedHashMap<>();
    private final SortedMap<String, String> playerKits = new TreeMap<>();

    public SortedMap<Integer, String> getKitRoomPages() {
        return kitRoomPages;
    }

    public Map<String, PublicKitEntry> getPublicKits() {
        return publicKits;
    }

    /**
     * Player kit and ender chest entries, keyed by their storage ID
     * ({@code <uuid><slot>} or {@code <uuid>ec<slot>}).
     */
    public SortedMap<String, String> getPlayerKits() {
        return playerKits;
    }

    public boolean isEmpty() {
        return kitRoomPages.isEmpty() && publicKits.isEmpty() && playerKits.isEmpty();
    }

    public int totalEntries() {
        return kitRoomPages.size() + publicKits.size() + playerKits.size();
    }
}
