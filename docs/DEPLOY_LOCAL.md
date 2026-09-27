# Deploying to a local Minecraft profile

`scripts/deploy_profile.sh` builds the mod and installs it into a launcher profile (default
`%APPDATA%/.minecraft/profiles/Minecraft-AI-1.21.11`), applies the LLM settings and makes sure logging
is on. Run it from Git Bash with the game closed:

```bash
bash scripts/deploy_profile.sh              # build + install + apply .env settings
bash scripts/deploy_profile.sh --check-key  # also ask the provider whether the key/model is accepted
bash scripts/deploy_profile.sh --no-build   # reuse build/libs
```

## LLM settings

The bot talks to any OpenAI-compatible chat-completions endpoint, or to Gemini when `baseUrl` is on
Google's `generativelanguage.googleapis.com` host. In `config/aibot.json`:

```json
"llm": { "apiKey": "...", "baseUrl": "https://generativelanguage.googleapis.com/v1beta/openai", "model": "gemini-3.5-flash-lite" }
```

* The section used to be called `deepseek`. That name is still read (and migrated by the deploy
  script); if both exist, `llm` wins.
* The API key can also come from the environment: `AIBOT_LLM_API_KEY`, or the legacy
  `DEEPSEEK_API_KEY`. The environment overrides the file.
* The shipped defaults still point at DeepSeek (`https://api.deepseek.com`, `deepseek-v4-flash`).

## Secrets stay out of git

Put the values in a local `.env` (copy `.env.example`). `.env` is gitignored; the game never reads it.
The deploy script parses it (it is never executed), writes the values into the profile's private
`config/aibot.json`, and never prints the key. Do not commit `aibot.json` from a profile either.

## Logging

`logging.enabled` is true by default: every launch starts a fresh session under
`<profile>/logs/aibot/sessions/<timestamp>/` (last 3 sessions kept), next to Minecraft's own
`logs/latest.log`. The deploy script re-enables it if a config had it switched off.
