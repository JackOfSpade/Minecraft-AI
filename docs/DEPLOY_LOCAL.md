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
Google's `generativelanguage.googleapis.com` host. In `config/minecraftai.json`:

```json
"llm": { "apiKey": "...", "baseUrl": "https://generativelanguage.googleapis.com/v1beta/openai", "model": "gemini-3.5-flash-lite" }
```

* The section used to be called `deepseek`. That name is still read (and migrated by the deploy
  script); if both exist, `llm` wins.
* The API key can also come from the environment: `MINECRAFTAI_LLM_API_KEY`, or the legacy
  `DEEPSEEK_API_KEY`. The environment overrides the file.
* The shipped defaults still point at DeepSeek (`https://api.deepseek.com`, `deepseek-v4-flash`).
* When the provider is overloaded or unreachable (HTTP 408/429/5xx, a timeout, a dropped connection), the
  bot's planning call is retried with exponential backoff and jitter for up to five minutes, honouring
  `Retry-After`. These retries do not use up the per-instruction model-call budget, the bot's running
  task carries on meanwhile, and a new message or a cancel ends the wait. If the provider stays down the
  bot says so in chat (and stays silent when the request had already finished), and its autonomous
  long-term-goal wake-ups pause for another five minutes instead of starting a new retry cycle at once.
  A reply the provider sent back unusable (empty, garbled, a function call without a name) is not an
  outage: the bot asks again as an ordinary planner call, which does use the model-call budget. Any other
  error (rejected key, bad request) fails at once; its reason is the `brain_hiccup` log line.
  `llm.retryCount` / `retryBackoffMs` therefore only govern the helper calls (chat routing, ambient chat).
* `deploy_profile.sh`'s own `.env` parsing separately accepts the pre-rename interim names
  `AIBOT_LLM_API_KEY` / `AIBOT_LLM_BASE_URL` / `AIBOT_LLM_MODEL` as a per-variable fallback (in the
  file or the environment) when the current `MINECRAFTAI_LLM_*` name is absent; it prints a warning
  naming whichever legacy names it used. `MINECRAFTAI_LLM_*` always wins when both are present.

## Secrets stay out of git

Put the values in a local `.env` (copy `.env.example`). `.env` is gitignored; the game never reads it.
The deploy script parses it (it is never executed), writes the values into the profile's private
`config/minecraftai.json`, and never prints the key. Do not commit `minecraftai.json` from a profile either.

## Logging

`logging.enabled` is true by default: every launch starts a fresh session under
`<profile>/logs/minecraftai/sessions/<timestamp>/` (last 3 sessions kept), next to Minecraft's own
`logs/latest.log`. The deploy script re-enables it if a config had it switched off.
