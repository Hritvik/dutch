Type: research
Status: resolved

# Select the cross-platform mobile stack

## Question

Which current cross-platform mobile stack best fits Dutch's iOS/Android MVP, considering delivery speed, maintainability, secure IAM integration, offline caching and drafts, push notifications, automated testing, native escape hatches, and compatibility with the Spring Boot backend?

## Answer

Use React Native with TypeScript and Expo development builds. Store revocable IAM session and refresh material in SecureStore, use SQLite for cached projections and drafts, place Expo Notifications behind a provider adapter, communicate with Spring Boot over REST/JSON, and combine Jest/component tests with device E2E tests on both platforms.

This is the fastest integrated MVP path while accepting React Native/Expo upgrade cadence and occasional native Swift/Kotlin work. Flutter is the strongest alternative but adds Dart; Kotlin/Compose Multiplatform offers stronger Kotlin/native affinity with more iOS and toolchain assembly.

Research context: branch `research/cross-platform-mobile-stack`, commit `2f466eaddf140cb7d1ad4f57ae1b7ee293deaaed`, artifact `research/cross-platform-mobile-stack.md`. All findings cite official primary sources.
