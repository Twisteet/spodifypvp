SPOTIFY PVP - Minecraft 1.21.11 (Fabric)

EASY SETUP
1. Make a free account at github.com and click New repository (name it spotifypvp).
2. Click "uploading an existing file", drag in EVERYTHING from this unzipped folder
   (including the .github folder), then click Commit changes.
   If the .github folder will not upload: click Add file > Create new file, type
   .github/workflows/build.yml as the name, and paste in the contents of that file.
3. Click the Actions tab. Wait about 3-5 minutes for the green tick.
4. Click the finished run, scroll to Artifacts, download SpotifyPVP-mod, unzip it.
5. Put the .jar inside into your Minecraft mods folder (needs Fabric + Fabric API).

IN GAME (one time)
  /spotify setup YOUR_CLIENT_ID     (from developer.spotify.com/dashboard)
  /spotify login                    (click the green link)
Redirect URI in the Spotify dashboard must be exactly: http://127.0.0.1:8888/callback
Spotify Premium is required to control playback.

Keys: [ previous   \ pause/resume   ] next   ; hide/show overlay
