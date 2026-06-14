# Polymr

Polymr is a user-centric LLM agent platform focused on control and visualization. 
It is a personal project, which means it has not seen widescale use and likely still has some bugs in it. That said, I use it daily for both coding and non-coding tasks to great effect.

If you're new here, I suggest checking out [the site](https://polymr-platform.github.io/) which explains in greater detail what polymr has to offer.
If you are feeling adventurous, head on over to [the releases](https://github.com/polymr-platform/polymr/releases) section to download a copy and try it yourself!
I welcome all feedback on the project itself and cool features that might fit the tool.

## Database

I built Polymr on postgresql and that's the only database that has seen extensive use.
Because I realize that it's quite a big hurdle to have a Postgresql running to test a tool, I've tried to fit an embedded database but it's not been easy to find one that can deal with the Polymr workload.
The default build will require a postgresql installation. For those who want to try it out, there is also an `msqldb` build which is embedded but pretty untested.

## Configuration

The first time you start Polymr, you will be greeted with a small configuration screen.
The resulting options will appear in the folder `~/.config/polymr`.

You can force Polymr to start with the configuration screen again by running `Polymr --config`.

By default Polymr will start on port 6655 but you can change it by adding the port parameter `Polymr --port 8080`.

Secret handling:

- by default Polymr generates `polymr.secrets.key` automatically on first startup, it is used to encrypt various data.
- `Polymr --secret <secret>` fixes the secret explicitly, which is useful for containers and more complex deployments
- `Polymr --secret-file <file>` reads the fixed secret from a file
- if both are provided, `--secret` takes precedence
- `Polymr --identity <identity>` fixes the Polymr server identity, which is useful in clustered or otherwise managed deployments

## Headless

By default Polymr will assume a desktop installation and it will:

- open the browser automatically once the local server is ready
- install a tray icon when the platform supports it

By setting it to `--headless` you avoid this, though it is best to allow `network` access then, so `Polymr --headless --listen-scope network`, otherwise you won't be able to access the site.

## Building

To create a new build you can use the included build script which uses jpackage to create an installable file for the target OS.
You can create a build with the default postgresql setup:

```bash
./build
```

There are also some experimental builds for example with hsqldb but these have not been tested:

```
./build msqldb
```

## Development

In development we use a two-server setup where the frontend proxies anything under `/api/` to the backend.
Vite is by default on port `5174`, the Quarkus backend on port `5050`.

Backend:

```bash
cd backend
./mvnw quarkus:dev
```

Frontend:

```bash
cd frontend
npm run dev
```
