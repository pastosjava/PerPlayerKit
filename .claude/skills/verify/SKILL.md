---
name: verify
description: Boot a throwaway Paper server with the built PerPlayerKit jar and drive commands from the console to verify changes end-to-end.
---

# Verifying PerPlayerKit on a live Paper server

## Build

```bash
mvn package            # produces target/PerPlayerKit-<version>.jar (shaded)
```

## Get a Paper jar (fill v3 API)

```bash
curl -s https://fill.papermc.io/v3/projects/paper | python3 -c \
  "import json,sys; print(list(json.load(sys.stdin)['versions'])[0])"   # latest version
DL=$(curl -s https://fill.papermc.io/v3/projects/paper/versions/<VER>/builds/latest \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['downloads']['server:default']['url'])")
curl -s -o paper.jar "$DL"
```

## Boot

```bash
SRV=$(mktemp -d /tmp/ppk-verify-XXXX)
# put paper.jar in $SRV, then:
echo "eula=true" > $SRV/eula.txt
mkdir -p $SRV/plugins && cp target/PerPlayerKit-*.jar $SRV/plugins/
printf "online-mode=false\nserver-port=25599\nlevel-type=flat\n" > $SRV/server.properties
tmux -L ppkverify new-session -d -s srv -x 220 -y 50 \
  "cd $SRV && java -Xms512M -Xmx1G -jar paper.jar nogui 2>&1 | tee console.log; sleep 3600"
# poll: grep -q 'Done (' $SRV/console.log   (boots in ~10s; plugin uses SQLite by default)
```

The trailing `sleep 3600` keeps the tmux session alive after `stop`, so you
can restart the server in the same session (plain `send-keys` the java
command again). Without it the session dies with the server.

## Drive

```bash
tmux -L ppkverify send-keys -t srv "kitroom save" Enter   # any command, no leading slash
tmux -L ppkverify capture-pane -t srv -p                  # see console
```

## Gotchas

- **Lang.send() messages to the console sender do not render in this
  headless/piped console** — not for new code, not for existing commands
  (`perplayerkit about` is silent too). Plugin `getLogger()` lines DO appear.
  Verify console-sender flows by their effects (files, config.yml, DB, log
  lines), or log alongside sending. Player-facing messages need a real client.
- Inspect storage directly: `sqlite3 $SRV/plugins/PerPlayerKit/database.db
  "SELECT KITID, length(KITDATA) FROM kits;"` (works while the server runs, WAL mode).
- Kit room pages only exist in the DB after `kitroom save` (defaults live in memory).
- Clean up: `tmux -L ppkverify kill-server; rm -rf $SRV`.
