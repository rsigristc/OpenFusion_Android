# Server profiles

OpenFusion Android v0.5.2 Beta includes two profile types:

- **FusionFall Retrobution** is the default, preconfigured profile.
- **Custom server** accepts a display name and an HTTPS API base URL.

Configure the active profile from **Settings → Server → Configure server**. Only use a custom
server you trust: the selected API receives the account credentials entered on the login screen.
Saved credentials are encrypted with Android Keystore and stored under a scope derived from the
normalized API URL, so they are not automatically reused after switching endpoints.

## Required API contract

A custom profile must provide the same launcher contract currently consumed by the Android
client:

- `GET /` returns `server_name`, `login_address`, and either `game_versions` or `game_version`.
- `GET /versions/{id}` (or `{id}.json`) returns `asset_url` and optionally `main_file_url`.
- `POST /auth` accepts a JSON username/password pair and returns a refresh token.
- `POST /auth/session` accepts that bearer token and returns `session_token`.
- `POST /cookie` accepts the session bearer token and returns a game cookie, username and expiry.

The profile URL may include a path prefix, such as `https://example.org/fusionfall/api`. Query
parameters, URL fragments, embedded usernames/passwords and non-HTTPS endpoints are rejected.
Game assets and the login address are supplied by the configured API and remain the server
operator's responsibility.

This interface is compatibility-based, not a universal OpenFusion discovery standard. Servers
using a different authentication or manifest format require a separate adapter in the client.
