# JARVIS — devir notu

Kişisel AI asistanı (Expo / React Native, Android öncelikli). Kullanıcı Türkçe konuşur; arayüz, Jarvis'in konuşması ve dokümanlar Türkçe, kod tanımlayıcıları İngilizce.

## Mimari kuralları
- `src/core/` platformdan bağımsızdır (React Native içe aktarmaz) ve `vitest` ile test edilir. İş kuralı değişikliği önce burada yapılır ve `npm test` yeşil kalmalı.
- Tüm veri değişiklikleri `Store.update` üzerinden ve iş kuralları `ops.ts` üzerinden geçer; böylece arayüz ve Jarvis (AI araçları) aynı kuralları kullanır ve her işlem `activity` kaydına düşer.
- Geri alınamaz işlemler (silme) Jarvis tarafından doğrudan yapılmaz: `request_deletion` → onay kartı (`pending`).
- Claude çağrıları `src/core/agent.ts` içindedir: `@anthropic-ai/sdk`, model `claude-opus-5-5`, `fallbacks: "default"`, sabit sistem istemi önbellekli; canlı durum her istekte kullanıcı mesajına eklenir (`prompt.ts`).
- Sahte/örnek veri yoktur. Tek içe aktarım, depodaki gerçek TÜBİTAK projesinin yapılacaklar listesidir (`seed.ts`).
- Ekranlar `src/app/` (Expo Router); her ekran `Screen` bileşeniyle kendi opak arka planını çizer (sekme/yığın ekranları üst üste durur).

This is an Expo/React Native mobile application. Prioritize mobile-first patterns, performance, and cross-platform compatibility.

## Expo has changed — do not trust your training data

Expo ships breaking changes every SDK release. APIs you remember are likely renamed, moved, or removed. Before writing any code that touches an Expo, EAS, or React Native API:

1. Read the major version of the `expo` package in `package.json`.
2. Fetch the matching versioned docs: `https://docs.expo.dev/versions/v<major>.0.0/`
3. For anything else, fetch https://docs.expo.dev/llms.txt — an index of all Expo docs with corrections to common LLM misconceptions. Follow its links to the specific page you need; never answer from memory.

## Commands

Use `bunx` instead of `npx` if the project uses bun (`bun.lock` present).

```bash
npx expo install <package>  # ALWAYS use instead of npm/yarn/pnpm/bun add — resolves SDK-compatible versions
npx expo start              # start the dev server
npx expo lint               # lint
npx tsc --noEmit            # typecheck
npx expo-doctor             # diagnose dependency and config issues
npx expo install --fix      # fix incompatible package versions
```

Run lint and typecheck before declaring any task done.

## Navigation & Routing

- Use **Expo Router** for all navigation. Routes live in `src/app/` — every file there is a screen, `_layout.tsx` files define navigators. Keep non-route code (components, hooks, utils) outside `src/app/`.
- Tabs come from `expo-router/js-tabs` (the `Tabs` export of `expo-router` is deprecated) with the custom `JarvisTabBar` (bottom bar on phones, left rail on wide screens).
- Import `Link`, `router`, and `useLocalSearchParams` from `expo-router`.
- Docs: https://docs.expo.dev/router/introduction.md

## Building with EAS

Use EAS to build, sign, and submit the app in the cloud (`eas build`, `eas submit`) and to ship over-the-air updates (`eas update`) — no local Xcode or Android Studio required. Run EAS CLI as `bunx eas-cli <command>` in Bun projects, or `npx eas-cli@latest <command>` otherwise; substitute that for bare `eas` in docs examples.
Docs: https://docs.expo.dev/eas/index.md

## Rules

- If `ios/` and `android/` directories do not exist, they are generated (Continuous Native Generation). Never create or edit them by hand — configure native behavior in `app.json` and config plugins.
- Expo Go only includes its bundled native modules. After adding a library with native code, the app needs a development build: `npx expo run:ios|android` locally, or `eas build --profile development`.
- Prefer recommended Expo modules over third-party libraries, and check your available skills before adding dependencies. Docs: https://docs.expo.dev/versions/latest/index.md
