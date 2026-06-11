# DonutFabric

A server-side Fabric mod for Minecraft 1.21.1 that recreates the **DonutSMP June 2026** economy and gameplay systems.

## Features

### Economy
- **Two currencies**: Money (earned via `/sell`, `/orders`, `/ah`) and Shards (AFK = 1/min, kills = 10)
- **Sell multiplier**: 1.0x → 3.0x based on cumulative sell revenue — rewards active farmers
- **No `/shop`**: Items must come from farms or players. Pure scarcity economy
- `/bal` — check your balance and multiplier
- `/pay <player> <amount>` — transfer money (configurable tax)
- `/sell hand` / `/sell all` — sell items at server worth prices with your current multiplier

### Orders System (`/orders`)
- Place bulk buy orders at your chosen price
- Other players fulfill orders to earn instant payment
- Orders escrow the full cost upfront (with tax)
- GUI and command interfaces
- 7-day expiry with automatic refunds

### Auction House (`/ah`)
- List items for sale with instant buyout
- Sort by price (asc/desc) or age (newest/oldest)
- Filter to your own listings
- 5% listing fee (non-refundable), 48-hour expiry
- Expired items returned via `/ah mine`

### Shards (`/shards`)
- Earn shards by being online (1/min) or killing players (10/kill)
- Spend shards in the Shard Shop on spawner eggs
- All spawners cost flat **1500 shards** (DonutSMP April 2026 price parity)

### Crystal PvP Combat
- 15-second combat tag on player hits
- **Logout punishment**: tagged players who disconnect are killed
- 12-second ender pearl cooldown
- Safezone worlds (configurable) where PvP is disabled
- Actionbar combat timer HUD

### RTP
- `/rtp` / `/wild` — teleport to a random safe location
- 5-minute cooldown, biome blacklist (no oceans/rivers)
- Blocked while in combat

## Installation

1. Install **Fabric Loader** for Minecraft 1.21.1
2. Install **Fabric API**
3. Drop `donutfabric-1.0.0.jar` into your `mods/` folder
4. Start the server — config files generate in `config/donutfabric/`

## Configuration

| File | Purpose |
|------|---------|
| `config.yml` | Database, economy tiers, taxes, combat settings |
| `worth.yml` | Item sell prices for `/sell` |
| `messages.yml` | All player-facing messages (supports `&` color codes) |
| `shards.yml` | Shard rewards and shop contents |
| `rtp.yml` | RTP radius, cooldown, biome blacklist |

## Database

Defaults to **SQLite** (zero-config, stored in `config/donutfabric/data.db`).  
Set `database-type: mongodb` and configure `mongodb-uri` in `config.yml` for MongoDB.

## Commands

| Command | Description |
|---------|-------------|
| `/bal [player]` | Check balance |
| `/pay <player> <amount>` | Pay a player |
| `/sell hand` / `/sell all` | Sell items |
| `/orders` | Open orders GUI |
| `/orders create <item> <qty> <price>` | Create buy order |
| `/orders cancel <id>` | Cancel your order |
| `/ah` | Open auction house GUI |
| `/ah sell <price>` | List held item |
| `/ah search <term>` | Search listings |
| `/ah mine` | View your listings |
| `/shards` | Check shard balance |
| `/shards shop` | Open shard shop |
| `/rtp` / `/wild` | Random teleport |

### Admin Commands
| Command | Permission |
|---------|-----------|
| `/shards give <player> <amount>` | OP level 2 |
| `/shards set <player> <amount>` | OP level 2 |

## Architecture

- **Server-side only** — no client mod required
- All DB I/O is async (dedicated thread pool, min 4 threads)
- Caffeine cache for economy data (30-min idle eviction, auto-flush on eviction)
- SQLite in WAL mode for concurrent reads
- All GUI interactions run back on the server thread

## Build

```bash
./gradlew build
# Output: build/libs/donutfabric-1.0.0.jar
```

Requires Java 21+.

## License

MIT — do whatever you want with it.
