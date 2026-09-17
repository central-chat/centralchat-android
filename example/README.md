# Central Chat example — Android

```bash
./gradlew :app:installDebug
```

Needs **JDK 17–22** (Gradle 8.9) and an `ANDROID_HOME`. Or open this folder in
Android Studio and run **app**.

Paste a **channel key** (`businessId|chatAccountId`) or a **mint user key**, tap
**Test**, then **Open central.chat**. *Test another experience* signs out, so the
same channel key comes back as a new anonymous visitor.

Skip the typing:

```bash
adb shell am start -n chat.central.widget.example/.MainActivity \
  --es entry "businessId|chatAccountId"
```

## What to read

[`MainActivity.kt`](app/src/main/java/chat/central/widget/example/MainActivity.kt),
and in it three lines — `init`, `show`, `reset`. Everything else is a text
field and two buttons.

Your app has no key field: it asks its backend for a mint user key on every
start and calls `init` with it.

It resolves `chat.central:centralchat` from
`https://static.central.chat/maven`, exactly as a customer does — this example
is the thing people copy, so it must not take a shortcut none of them can take.

To work against the library source beside it instead, uncomment the
`includeBuild` line in [`settings.gradle.kts`](settings.gradle.kts).
