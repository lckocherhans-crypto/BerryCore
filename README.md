# BerryCore (Paper 26.2 / Java 25)

One plugin: **Teams, Berries economy, Homes, Stars, Sell GUI, Shulker selling, Settings, Player Market** + PlaceholderAPI.
Java players get Paper **dialogs**, Bedrock/Geyser players get **chest menus** (+ chat prompts for text input).

## Build on GitHub (no local Java needed)
1. Create a new GitHub repo and upload everything in this folder (keep `.github/workflows/build.yml`).
2. Open the **Actions** tab -> *Build BerryCore* -> wait for the green check.
3. Open the run -> **Artifacts** -> download `BerryCore` -> unzip -> `BerryCore-1.0.0.jar`.
4. Drop the jar in `plugins/`, start the server once, edit the `.yml` files, `/berrycore reload`.

If the build fails, copy the red error lines and send them back - the most likely spots are listed at the bottom.

Optional plugins: PlaceholderAPI, Floodgate/Geyser (Bedrock detection works without them via the Floodgate UUID format).

## Config files (plugins/BerryCore/)
config.yml (Bedrock + prompts) | messages.yml | teams.yml | economy.yml | market.yml | homes.yml | stars.yml | sellgui.yml | shulker.yml | settings.yml
Data lives in `data/*.yml`.
Each feature file has `ui.java: dialog|chest`. Bedrock is always chest.

## Commands
| Feature | Commands |
|---|---|
| Hub | `/berries` (economy menu), `/settings`, `/berrycore reload|save` |
| Teams | `/team` (menu) `create|disband|invite|accept|deny|leave|kick|promote|demote|transfer|rename|color|ff|chat|sethome|delhome|home|homes|members|info|list`, `/tc [msg]` toggles/uses team chat |
| Economy | `/balance [p]`, `/pay [p] [amt]`, `/baltop`, `/eco give|take|set|reset`, `/sell` (GUI) `/sell hand|all|worth`, `/worth` |
| Market | `/market [search]` (`/ah`), `/shops`, `/shop` (manage) `sell [price]|name|icon|open|close|visit` |
| Homes | `/home [n]`, `/homes`, `/sethome [n] [display name]`, `/delhome`, `/renamehome`, `/homeicon <home> [material|hand]`, `/buyhome` |
| Stars | `/stars [p|shop|top]`, `/stars give|take|set|reset` (admin), `/starshop` |

Team colours: `/team color #ff0000` (solid) or `/team color #ff0000 #0000ff` (gradient, up to 4 colours).

## Permissions
`berrycore.admin`, `berrycore.eco.admin`, `berrycore.stars.admin`, `berrycore.market.admin`, `berrycore.teams.admin`, `berrycore.teams.spy`,
`berrycore.teams.gradient` (default true), `berrycore.tp.bypass`, `berrycore.homes.bypass`, `berrycore.homes.format`.
Rank perks are plain permission nodes you choose in the configs (`permission-limits` in homes.yml, `listing-limits` in market.yml, `sell-multipliers` in economy.yml).

## PlaceholderAPI (`%berrycore_...%`)
Economy: `balance`, `balance_formatted`, `balance_fancy`, `baltop_<n>_name`, `baltop_<n>_value`, `currency_name`, `currency_symbol`
Stars: `stars`, `stars_next` (seconds), `stars_next_minutes`, `starstop_<n>_name`, `starstop_<n>_value`
Homes: `homes`, `homes_max`, `homes_extra`, `homes_next_cost`
Teams: `team`, `team_display` (coloured/gradient), `team_minimessage`, `team_color`, `team_role`, `team_members`, `team_online`, `team_homes`, `team_chat`, `team_ff`, `teams_total`
Market: `shop_listings`, `market_listings`
Settings: `setting_<id>` with dashes as underscores, e.g. `%berrycore_setting_confirm_market_purchase%`
PlaceholderAPI placeholders also work inside every message, menu title and item name in the configs.

## Known gaps / ideas
- No `/orders` (buy orders) yet - the market is listing based.
- Not a Vault provider (other plugins can't use Berries yet) - easy to add.
- Market listings never expire.

## If the build fails, check these first
1. `Dlg.java` - the only file that touches the Paper Dialog API. Signatures may differ slightly between Paper builds.
2. `pom.xml` - `paper.version` is pinned to `26.2.build.56-alpha`; bump it to the newest 26.2 build.
3. PlaceholderAPI repository/version in `pom.xml`.
