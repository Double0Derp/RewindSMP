# Time Rewind

Build the plugin:  `mvn package`  ->  `target/time-rewind-1.0.0.jar` into `plugins/`.
(Set `paper.version` in pom.xml to your server's Paper version first.)

## Clock gauge resource pack
The gauge is drawn with custom font glyphs, so players need the pack.

1. Upload `rewind-hud-pack.zip` somewhere with a direct download link.
2. In `server.properties`:
       resource-pack=<direct link>
       resource-pack-sha1=<sha1 of the zip>
       require-resource-pack=true
3. Restart. No pack yet? Set `hud-mode: bossbar` in the plugin config.

Gauge too high/low? Edit ASCENT in `tools/generate_pack.py`
(lower number = lower on screen), re-run it, re-zip the *contents* of `resourcepack/`.
