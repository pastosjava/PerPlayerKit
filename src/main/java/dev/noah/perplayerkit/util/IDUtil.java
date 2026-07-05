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
package dev.noah.perplayerkit.util;

import java.util.UUID;
import java.util.regex.Pattern;

public class IDUtil {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int UUID_LENGTH = 36;

    public static String getPlayerKitId(UUID playerId, int slot) {
        return playerId.toString() + slot;
    }

    public static String getECId(UUID playerId, int slot) {
        return playerId.toString() + "ec" + slot;
    }

    public static String getPublicKitId(String name) {
        return "public" + name;
    }

    public static String getKitRoomId(int slot) {
        return "kitroom" + slot;
    }

    /**
     * Matches per-player entries only: kit IDs ({@code <uuid><slot>}) and ender
     * chest IDs ({@code <uuid>ec<slot>}). Public kits and the kit room are
     * excluded. Bounded by the absolute {@link KitSlots#MAX_LIMIT}, deliberately
     * not the configured max-kits — the database may hold kits above a lowered
     * limit and bulk operations must still reach them.
     */
    public static boolean isPlayerDataId(String id) {
        if (id == null || id.length() < UUID_LENGTH + 1) {
            return false;
        }
        if (!UUID_PATTERN.matcher(id.substring(0, UUID_LENGTH)).matches()) {
            return false;
        }

        String suffix = id.substring(UUID_LENGTH);
        if (suffix.startsWith("ec")) {
            suffix = suffix.substring(2);
        }
        return KitSlots.parseSlotSuffix(suffix) != null;
    }

}
