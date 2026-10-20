# Plan: LandSAR Base on a VPS, behind Authentik, reachable from CloudTAK

Status: **plan**. Nothing here has been deployed. Each statement is tagged:

- **[verified here]**: checked against the running instance on the development machine.
- **[untested]**: written from the documentation of the tool involved or from general practice; try it and correct this document.

Target: a Docker install on the existing `tak-network` stack, next to TAK Server, Authentik (with its LDAP outpost) and CloudTAK.

## 1. How LandSAR authenticates today

- **Apache Shiro 1.4 with a form login. The users are in a database, not a flat file.** `shiro.ini` points a JDBC
  realm at the embedded Apache Derby database `landsar` (folder `landsar/`, served on `127.0.0.1:1621`, no password).
  Tables `USERS` (username, password_hash, password_salt), `ROLES`, `USER_ROLES`. One user, `admin`, with roles
  `adminRole` and `userRole`; the README's demo password. [verified here]
- **One authorization rule:** `/** = authc, roles[userRole]`; only `/webmap/landsar/public/**` is anonymous. [verified here]
- **LandSAR does not use the user's identity anywhere.** No plugin code reads the logged-in user; everyone who gets
  in shares one workspace of lost-person instances. Authentication is a gate at the web server, nothing more. [verified here]
- **Login form:** a POST of `username`, `password` and a `remember` checkbox to the login page
  (`/webmap/landsar/public/account/login.html`). [verified here]
- **Ports** [verified here]:
  - 8800: the web UI and the websocket paths `/FINDER/...`, TLS with a keystore, **login required**.
  - 8082: a second web server with `use_auth: false` serving a websocket servlet; listens on **all interfaces**.
  - 9095: Roger's UI service; listens on **all interfaces**; answers 404 at `/`.
  - 1621: Derby, localhost only.
  I did not enumerate what 8082 and 9095 accept. Treat them as unauthenticated and never publish them.
- Shiro's LDAP classes (`JndiLdapRealm`, `JndiLdapContextFactory`) are in this build. [verified here]

## 2. Choosing how Authentik fits

Your identity backbone: Authentik users, with TAK Server (and so CloudTAK) logging in through Authentik's LDAP outpost.

| | A. LDAP realm (recommended first) | B. Authentik proxy outpost (forward auth) |
|---|---|---|
| What the user sees | LandSAR's own login page; the **same username and password as TAK/CloudTAK** | An Authentik login (SSO), then straight into LandSAR |
| Change to LandSAR | `shiro.ini` only (no code) | `use_auth: false` on the 8800 plugin; network isolation |
| New moving parts | none (the LDAP outpost already exists) | reverse proxy with `auth_request` + a proxy provider and outpost |
| MFA / SSO | no (password only; check Authentik's documentation for MFA over LDAP) | yes |
| Who may use it | bind policy on the Authentik application/group | policy on the Authentik application |
| Risk | LandSAR sees passwords; plain `ldap://` inside Docker unless you set up LDAPS | if LandSAR is ever reachable around the proxy, it is open to everyone |

A fits how CloudTAK already authenticates, so people use one set of credentials. B is the better end state if
CloudTAK moves to OIDC. They are not exclusive: A can be added now and B later (B replaces A's login).

## 3. Architecture (option A)

```
Internet -- TLS --> reverse proxy (nginx/Traefik) --http--> landsar:8800   (tak-network, expose only)
                                                               |
                                              ldap://ldap-outpost:3389 (Authentik LDAP outpost, same one as TAK Server)
CloudTAK --http--> landsar:8800   (inside tak-network; see section 8)
8082, 9095, 1621: not published, not routed.
```

## 4. Build the image [untested]

The repo's Dockerfile is Ubuntu 20.04 + Java 8 (both end-of-life) and assumes the original start script. Build a
new one: a JDK stage that prepares everything, and a JRE stage that runs it.

```dockerfile
# --- build ---
FROM eclipse-temurin:11-jdk AS build
RUN apt-get update && apt-get install -y python3 unzip wget && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
ENV JAVAC=javac
RUN ./gradlew --no-daemon copyRuntimeLibs
RUN wget -q https://github.com/NASAWorldWind/WorldWindJava/releases/download/v2.1.0/worldwind-v2.1.0.zip \
 && unzip -q -j worldwind-v2.1.0.zip worldwind.jar -d dependencies/ && rm worldwind-v2.1.0.zip
RUN python3 tools/apply_patches.py && python3 tools/build_terrain_walk.py

# --- run ---
FROM eclipse-temurin:11-jre
WORKDIR /opt/landsar
COPY --from=build /src /opt/landsar
ENV JAVA=java
EXPOSE 8800
CMD ["sh", "-c", "exec java -cp 'roger-core-dependencies/dependencies/*:roger-core-1.4.0-fullRelease.jar' com.bbn.roger.launcher.CommandLineLauncher --configFile config.json"]
```

The repo's own `Dockerfile` only builds a bare Ubuntu/Java 8 base that expects the project mounted at `/opt/roger`;
it contains no application. Use `Dockerfile.vps` (`build: {context: ./landsar-base, dockerfile: Dockerfile.vps}`).
`.dockerignore` excludes `.git`, the caches and the run-time state folders. It must not exclude `landsar/`: that is the
Derby user database holding the demo `admin` login, and the container cannot log anyone in without it.
`tools/apply_patches.py` compiles Java, which is why the build stage is a JDK. The natives in `nativelibs/` are
Linux x86-64: use an x86-64 VPS, or expect to rework that.

## 5. Data and state [untested]

- **Cache:** `geocache-az-state/` (3.2 GB). Copy it from the development machine (`rsync`), or rebuild it on the VPS
  with `tools/build_geocache.py` (needs GDAL's Python bindings; about 1.5 hours of downloads). Mount read-only.
- **Volumes to persist** (these hold state): `landsar/` (the Derby database), `scenariosAndSearches/`, `AreaData/`,
  `requests/`, `web/missionPackages/`, `afterActionReports/`, logs.
- **Memory:** the patched cache loader keeps every tile it has read (about 6.4 MB each); a heavily used statewide cache
  can reach several GB of heap on top of the JVM. Plan for 8 GB RAM or more, and set `-Xmx` explicitly.
- `web/` is rewritten from the plugin jar at every start (this is why `web/maps.js` always shows as modified).

## 6. LandSAR configuration

**`config.json`** (the 8800 plugin) [untested]: `"use_tls": false` (TLS ends at the proxy); keep `port` 8800.
The 8082 plugin (`ServerPlugin_no_auth`): try removing it; if something needs it, it must still not be published.
Do not add `ports:` to the compose service; use `expose:`.

**`shiro.ini`, option A** [untested]. Replace the realm and the URL rules:

```ini
[main]
ldapContextFactory = org.apache.shiro.realm.ldap.JndiLdapContextFactory
ldapContextFactory.url = ldap://ldap-outpost:3389
ldapRealm = org.apache.shiro.realm.ldap.JndiLdapRealm
ldapRealm.contextFactory = $ldapContextFactory
ldapRealm.userDnTemplate = cn={0},ou=users,dc=ldap,dc=goauthentik,dc=io
securityManager.realms = $ldapRealm
shiro.loginUrl = /webmap/landsar/public/account/login.html
authc.successUrl = /webmap/landsar/landsar.html
# generate a new key: python3 -c "import os;print('0x'+os.urandom(16).hex())"
securityManager.rememberMeManager.cipherKey = <NEW KEY>

[urls]
/webmap/landsar/public/account/login.html = authc
/webmap/landsar/public/account/logout = logout
/webmap/landsar/about/** = authc
/webmap/landsar/public/** = anon
/** = authc
```

- The last rule drops `roles[userRole]`: LDAP gives Shiro no such role, so the old rule would lock everyone out.
- Use your outpost's real base DN and port: the values above are Authentik's usual defaults (the LDAP outpost's
  base DN is configurable per provider), not read from your system. Check with
  `ldapsearch -H ldap://ldap-outpost:3389 -D "cn=<user>,ou=users,<base DN>" -W -b "<base DN>"` from a container on `tak-network`.
- **Who may log in:** Shiro cannot filter by group here. In Authentik, give LandSAR its own application and LDAP
  provider (or reuse TAK's), and bind a group such as `landsar-users` to it, so only members can bind. Verify that one
  outpost can serve two providers, or run a second outpost.
- Consider LDAPS between the containers if the passwords cross anything other than the Docker network.

**Option B** [untested]: in Authentik, create a *Proxy provider* in forward-auth (single application) mode for
`landsar.<domain>` and add it to an outpost; Authentik's provider page shows the exact nginx/Traefik snippet to use.
Set `use_auth` to `false` on the 8800 plugin, and keep the container off every network but the proxy's.

## 7. Reverse proxy [untested]

The web UI opens websockets to `wss://<host>/FINDER/...`, so the proxy must pass the upgrade and tolerate idle time:

```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }
server {
  listen 443 ssl http2;
  server_name landsar.example.org;
  # ssl_certificate / ssl_certificate_key ...
  location / {
    proxy_pass http://landsar:8800;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 3600s;
  }
}
```

## 8. Compose service [untested]

```yaml
services:
  landsar:
    build: ./landsar-base
    restart: unless-stopped
    networks: [tak-network]
    expose: ["8800"]               # never "ports:"
    environment: ["JAVA_TOOL_OPTIONS=-Xmx6g"]
    volumes:
      - ./geocache-az-state:/opt/landsar/geocache-az-state:ro
      - ./shiro.ini:/opt/landsar/shiro.ini:ro
      - landsar-db:/opt/landsar/landsar
      - landsar-searches:/opt/landsar/scenariosAndSearches
      - landsar-areadata:/opt/landsar/AreaData
      - landsar-missions:/opt/landsar/web/missionPackages
volumes: { landsar-db: {}, landsar-searches: {}, landsar-areadata: {}, landsar-missions: {} }
networks: { tak-network: { external: true } }
```

## 9. How CloudTAK would use it

1. **Link or embed the UI.** Simplest. With option A the user logs in once to LandSAR with the TAK credentials; with
   option B, once through Authentik. Embedding in a frame may need the proxy to allow framing. [untested]
2. **A CloudTAK plugin that drives LandSAR** (as your other CloudTAK plugins talk to external services). LandSAR has no
   API tokens. A server-side plugin would POST the login form with a service account (an Authentik user in the
   LandSAR group), keep the session cookie, then use the websocket paths the UI uses (`/FINDER/newLostPersonInstance/pointLKP`,
   `/FINDER/newSearch`, `/FINDER/deleteLostPersonInstance`, ...). The JSON bodies are the ones the UI sends; the server
   saves each one in `requests/LPI_*.json`, which is the easiest reference. [verified here: the paths and the saved
   bodies; untested: driving it from outside]
3. **Use the output.** Each search writes KMZ overlays under `web/missionPackages/<id>/`, served behind the same login.
   CloudTAK can load a KMZ by URL, so a plugin can fetch and display it. [untested]

Plan for the web UI's results to be per-search rather than per-user: LandSAR has no notion of an owner (section 1).

## 10. Hardening checklist

- Remove or change `admin`/`demotime`. With option A the Derby user table is no longer consulted for login.
- Replace the committed keystore and its password (`landsar-base.jks`), or terminate TLS only at the proxy.
- Replace the fixed "remember me" cipher key in `shiro.ini`.
- Publish only the proxy's 443. Do not publish 8800, 8082, 9095 or 1621.
- Keep the Derby port local; it has no password.
- Run the container as a non-root user; mount the cache read-only; keep the image rebuilt from patched, current bases.
- Log and review `landsar-server.log` for repeated failed logins; Shiro does not rate-limit them (put limits in the proxy
  or in Authentik's bind flow).

## 11. Order of work

1. Build the image and run it on the VPS with the demo login, on the internal network only; open the UI through an SSH tunnel. **Done 2026-10-20: login and a Flagstaff search work (section 13).**
2. Add the proxy and TLS; confirm a search works through it (websockets).
3. Switch `shiro.ini` to LDAP (option A); confirm a TAK user can log in and a non-member cannot.
4. Remove the demo admin; rotate the keystore and cipher key; check the port list from outside.
5. Wire CloudTAK (section 9), starting with the link.

## 12. Unknowns to settle by trying

- What the 8082 servlet and the 9095 service accept, and whether 8082 can be removed.
- That the LDAP realm works in this plugin's class loader (the classes exist; the login has not been run).
- Whether Authentik's LDAP outpost serves two providers, and the real base DN in your deployment.
- Framing the UI inside CloudTAK; the session cookie's behaviour through the proxy.
- Memory and start-up time with the statewide cache under load.

## 13. First deployment: what happened [verified on the VPS, 2026-10-20]

The image built from `Dockerfile.vps`, the patches applied, the demo login worked through an SSH tunnel and a Flagstaff
search ran against the statewide cache. Problems met, in the order they appeared:

| Symptom | Cause | Fix |
|---|---|---|
| Container restarts; log says `/opt/roger/logs/dockerlog.log: No such file` | The repo's `Dockerfile` is only a bare Java 8 base that expects the project mounted at `/opt/roger`. | Build with `dockerfile: Dockerfile.vps`. |
| Build fails: `No such file ... /opt/homebrew/opt/openjdk@11/bin/javac` | The patch scripts default to the Mac's `javac`. | `ENV JAVAC=javac` in the build stage (now in `Dockerfile.vps`). |
| `error mounting ... shiro.ini ... not a directory` | The mounted file did not exist, so Docker created a root-owned directory of that name, and recreates it at every `up` while the mount line stays. | Delete or comment the mount line, then `rmdir shiro.ini` (use `sudo` if needed). Mount a real file only once it exists. |
| Tunnel: `connect failed: Connection refused` | Container not running (see rows above); nothing listened on `127.0.0.1:8800`. | `docker compose ps` and `logs` first. |
| Browser closes the connection straight away | Self-signed certificate (`use_tls` is on). | Use `https://`, accept the warning. |
| Search: "bounding box is outside the elevation model" | The bind-mounted `geocache-az-state` was empty when the server started. The cache is read **once at startup** (log line `Directory contains 0 files`, bounding box `Infinity`). | Copy the cache in (`rsync`), then `docker compose restart landsar`. |

Commands that worked:

- Tunnel: `ssh -N -L 9800:localhost:8800 user@host`, then `https://localhost:9800/`. Needs `ports: ["127.0.0.1:8800:8800"]` on the service.
- Cache copy: `rsync -avP --partial geocache-az-state/ user@host:tak-stack/geocache-az-state/` (3.2 GB, resumable; `chown` the target first if Docker created it).
- Check the cache loaded: `docker compose logs landsar | grep -E "Directory contains|Offline data cache has bounding box"`; expect about 508 entries and a box covering the state plus margin.
- Real errors: `docker compose logs landsar | grep -v DEBUG | tail -60` (the log is full of Jetty `DEBUG` lines until `logback.xml` is lowered).

Corrections to the sketches above:

- Section 8's `networks: { tak-network: { external: true } }` fails when the stack already names its default network
  `tak-network`; then the service needs no `networks:` entry at all.
- Run `docker compose` in the stack folder, and `git` in the `landsar-base` clone (a different repository).
- The `shiro.ini` mount in section 8 is optional until `shiro.ini` is edited for LDAP; the image carries the repo copy.
- The VPS runs whatever is **committed** on the branch: local, uncommitted TerrainWalk changes are not in the image.
