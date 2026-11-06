# MC Jerry

A NeoForge mod for Minecraft **1.21.1** (same setup as Create / Create Aeronautics) that lets you summon Jerry:
an absurdly powerful guy who never starts a fight, runs a shop, does favors for offerings, and remembers who was nice to him.
He talks using the same Ollama model as the Jerry 2.0 Discord bot.

## Install
1. Open the latest run under **Actions → Build**, download the `jerry-mod` artifact, unzip it.
2. Put `jerry-1.0.0.jar` in the `mods` folder of the server **and** every player's client.

## Playing
| | |
|---|---|
| `/jerry summon` | Call Jerry (one per dimension; summoning again brings him to you) |
| Right-click Jerry | Shop (vanilla trade screen) |
| Sneak + right-click with an item | Gift — raises favor |
| Say "jerry" in chat near him | Talk to him |
| `/jerry offerings` | What he'll do and what it costs |
| `/jerry offer <id> [target]` | Pay for a favor. Target can be a player or `@e[type=wither,limit=1]` |
| `/jerry favor [player]` | How much he likes you |
| `/jerry dismiss` | Send him away (summoner or op) |
| `/jerry reload` · `/jerry setfavor` | Op only |

## Config (server side)
Everything lives in `config/jerry/` on the server: `general.json`, `shop.json`, `offerings.json`, plus a `README.txt`
listing every offering action type. Edit, then `/jerry reload` — it applies to everyone immediately. Clients need no config.

Set `llm.url` in `general.json` to the machine running Ollama (e.g. `http://jerry:11434`).

## Building
CI builds on every push and boots a test server with the mod. Locally: `gradle build` (Java 21, Gradle 8.14).
