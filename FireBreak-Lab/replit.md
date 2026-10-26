# FireBreak Lab

Wildfire water-line research application for a Turkish TÜBİTAK high-school project. See README.md for assumptions, provenance, operation and deployment limitations.

## Run & Operate

- `pnpm --filter @workspace/api-server run dev` — run the API server (port 5000)
- `pnpm run typecheck` — full typecheck across all packages
- `pnpm run build` — typecheck + build all packages
- `pnpm --filter @workspace/api-spec run codegen` — regenerate API hooks and Zod schemas from the OpenAPI spec
- `pnpm --filter @workspace/db run push` — push DB schema changes (dev only)
- No paid API credentials needed. API uses SQLite, not the scaffold PostgreSQL library.

## Stack

- pnpm workspaces, Node.js 24, TypeScript 5.9
- API: Python FastAPI, NumPy simulation, WebSocket replay
- DB: SQLite; persistent disk required for production
- Validation: Zod (`zod/v4`), `drizzle-zod`
- API codegen: Orval (from OpenAPI spec)
- Build: esbuild (CJS bundle)

## Where things live

_Populate as you build — short repo map plus pointers to the source-of-truth file for DB schema, API contracts, theme files, etc._

## Architecture decisions

_Populate as you build — non-obvious choices a reader couldn't infer from the code (3-5 bullets)._

## Product

_Describe the high-level user-facing capabilities of this app once they exist._

## User preferences

- Real open data only; never synthesize fuel/terrain or experimental results when providers fail.
- Turkish default with English toggle; responsive web platform explicitly selected.
- Google imagery must never be used for analysis.

## Gotchas

_Populate as you build — sharp edges, "always run X before Y" rules._

## Pointers

- See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details
