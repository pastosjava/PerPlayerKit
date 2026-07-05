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

import dev.noah.perplayerkit.KitManager;
import dev.noah.perplayerkit.PublicKit;
import dev.noah.perplayerkit.kitdata.KitDataSnapshot.PublicKitEntry;
import dev.noah.perplayerkit.storage.StorageManager;
import dev.noah.perplayerkit.util.IDUtil;
import dev.noah.perplayerkit.util.Serializer;

import java.util.Locale;
import java.util.Set;

/**
 * Builds kit data snapshots from storage for export and applies imported
 * snapshots back. Item data blobs are copied verbatim between the storage
 * backend and the snapshot, so this works identically with every backend
 * (SQLite, MySQL, PostgreSQL, Redis, YAML).
 * <p>
 * Bukkit-side effects of an import (config entries for public kits, cache
 * reloads) are handled by the command; this class only touches storage.
 */
public class KitDataService {

    /** What to include in an export. */
    public enum Scope {
        ALL, KITROOM, PUBLICKITS, PLAYERKITS;

        public static Scope fromString(String input) {
            try {
                return valueOf(input.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public String lowercase() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What an import would overwrite. Public kits count as existing when they
     * are registered in the config even without saved item data, because the
     * import also rewrites their config name and icon.
     */
    public record ImportPlan(int kitRoomPagesOverwritten, int publicKitsOverwritten, int playerKitsOverwritten) {
        public boolean overwritesAnything() {
            return kitRoomPagesOverwritten > 0 || publicKitsOverwritten > 0 || playerKitsOverwritten > 0;
        }
    }

    private final StorageManager storage;
    private final KitManager kitManager;

    public KitDataService(StorageManager storage, KitManager kitManager) {
        this.storage = storage;
        this.kitManager = kitManager;
    }

    /**
     * Reads the requested data out of storage. Kit room pages and public kits
     * that have never been saved to the database are skipped (public kits are
     * still included config-only, without item data).
     */
    public KitDataSnapshot buildSnapshot(Scope scope) {
        KitDataSnapshot snapshot = new KitDataSnapshot();

        if (scope == Scope.ALL || scope == Scope.KITROOM) {
            for (int page = 0; page < KitDataFile.KIT_ROOM_PAGES; page++) {
                String data = storage.getKitDataByID(IDUtil.getKitRoomId(page));
                if (isPresent(data)) {
                    snapshot.getKitRoomPages().put(page, data);
                }
            }
        }

        if (scope == Scope.ALL || scope == Scope.PUBLICKITS) {
            for (PublicKit kit : kitManager.getPublicKitList()) {
                String data = storage.getKitDataByID(IDUtil.getPublicKitId(kit.id));
                snapshot.getPublicKits().put(kit.id, new PublicKitEntry(
                        kit.id,
                        kit.name == null ? kit.id : kit.name,
                        kit.icon == null ? "CHEST" : kit.icon.name(),
                        isPresent(data) ? data : null));
            }
        }

        if (scope == Scope.ALL || scope == Scope.PLAYERKITS) {
            for (String id : storage.getAllKitIDs()) {
                if (!IDUtil.isPlayerDataId(id)) {
                    continue;
                }
                String data = storage.getKitDataByID(id);
                if (isPresent(data)) {
                    snapshot.getPlayerKits().put(id, data);
                }
            }
        }

        return snapshot;
    }

    /** Determines what applying the snapshot would overwrite. */
    public ImportPlan analyzeImport(KitDataSnapshot snapshot) {
        Set<String> existingIds = storage.getAllKitIDs();

        int kitRoomPages = 0;
        for (int page : snapshot.getKitRoomPages().keySet()) {
            if (existingIds.contains(IDUtil.getKitRoomId(page))) {
                kitRoomPages++;
            }
        }

        Set<String> configuredPublicKits = kitManager.getPublicKitList().stream()
                .map(kit -> kit.id)
                .collect(java.util.stream.Collectors.toSet());
        int publicKits = 0;
        for (PublicKitEntry kit : snapshot.getPublicKits().values()) {
            if (configuredPublicKits.contains(kit.id())
                    || existingIds.contains(IDUtil.getPublicKitId(kit.id()))) {
                publicKits++;
            }
        }

        int playerKits = 0;
        for (String id : snapshot.getPlayerKits().keySet()) {
            if (existingIds.contains(id)) {
                playerKits++;
            }
        }

        return new ImportPlan(kitRoomPages, publicKits, playerKits);
    }

    /**
     * Decodes every item data blob in the snapshot, so a file with data that
     * cannot be turned back into item stacks is rejected as a whole before
     * anything is written.
     */
    public void validateItemData(KitDataSnapshot snapshot) throws InvalidKitDataException {
        for (var entry : snapshot.getKitRoomPages().entrySet()) {
            validateBlob(entry.getValue(), "kit room page " + entry.getKey());
        }
        for (PublicKitEntry kit : snapshot.getPublicKits().values()) {
            if (kit.data() != null) {
                validateBlob(kit.data(), "public kit " + kit.id());
            }
        }
        for (var entry : snapshot.getPlayerKits().entrySet()) {
            validateBlob(entry.getValue(), "player kit " + entry.getKey());
        }
    }

    /** Writes every item data blob in the snapshot to storage, verbatim. */
    public void applyToStorage(KitDataSnapshot snapshot) {
        for (var entry : snapshot.getKitRoomPages().entrySet()) {
            storage.saveKitDataByID(IDUtil.getKitRoomId(entry.getKey()), entry.getValue());
        }
        for (PublicKitEntry kit : snapshot.getPublicKits().values()) {
            if (kit.data() != null) {
                storage.saveKitDataByID(IDUtil.getPublicKitId(kit.id()), kit.data());
            }
        }
        for (var entry : snapshot.getPlayerKits().entrySet()) {
            storage.saveKitDataByID(entry.getKey(), entry.getValue());
        }
    }

    private void validateBlob(String data, String what) throws InvalidKitDataException {
        try {
            Serializer.itemStackArrayFromBase64(data);
        } catch (Exception e) {
            throw new InvalidKitDataException("item data for " + what + " could not be decoded", e);
        }
    }

    private static boolean isPresent(String data) {
        return data != null && !data.equalsIgnoreCase("error");
    }
}
